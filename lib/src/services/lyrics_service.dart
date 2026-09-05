import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/song_data.dart';
import '../utils/artists.dart';
import '../utils/hashing.dart';

class LrcLine {
  final Duration timestamp;
  final String text;
  const LrcLine(this.timestamp, this.text);
}

/// Log solo en debug: en release estos prints spameaban logcat en cada
/// canción durante el sync masivo.
void _lrcLog(String message) {
  if (kDebugMode) debugPrint(message);
}

/// HTTP con reintentos y backoff exponencial (600ms → 1.2s → 2.4s).
/// Reintenta errores de red/timeout y 429/5xx (rate-limit de lrclib en
/// syncs masivos). Devuelve null si agota los intentos.
Future<http.Response?> _lrcRequest(
  Future<http.Response> Function() request, {
  required String label,
}) async {
  const delays = [
    Duration(milliseconds: 600),
    Duration(milliseconds: 1200),
    Duration(milliseconds: 2400),
  ];
  for (var attempt = 0; ; attempt++) {
    try {
      final res = await request().timeout(const Duration(seconds: 10));
      if ((res.statusCode == 429 || res.statusCode >= 500) &&
          attempt < delays.length) {
        _lrcLog('$label ${res.statusCode}, reintento ${attempt + 1}');
        await Future.delayed(delays[attempt]);
        continue;
      }
      return res;
    } catch (e) {
      if (attempt >= delays.length) {
        _lrcLog('$label error definitivo: $e');
        return null;
      }
      _lrcLog('$label error ($e), reintento ${attempt + 1}');
      await Future.delayed(delays[attempt]);
    }
  }
}

class LyricsService {
  static const _prefsTreeUri = 'tree_uri';
  static const _prefsFilePaths = 'file_paths';
  static const _lrcFolderName = '8th_lrc';

  /// Instancia compartida: _cachedDir/_triedMusicDir/_prefs viven aquí.
  /// Antes cada LyricsView creaba su propio servicio y re-probaba la
  /// sonda de escritura del directorio en cada apertura.
  static final LyricsService shared = LyricsService();

  Directory? _cachedDir;
  bool _triedMusicDir = false;
  SharedPreferences? _prefs;

  Future<SharedPreferences> _prefsInstance() async =>
      _prefs ??= await SharedPreferences.getInstance();

  // Obtiene directorio 8th_lrc con cache:
  // - Prioridad: carpeta de música seleccionada / 8th_lrc  (p.ej. 8th/8th_lrc)
  // - Si falla por scoped storage (Android 11+ Operation not permitted), cachea fallback
  //   para no spamear permission_handler ni probes en cada canción.
  Future<Directory> getLrcDir() async {
    if (_cachedDir != null && await _cachedDir!.exists()) {
      return _cachedDir!;
    }

    // Si ya probamos musicDir y falló, ir directo a fallback cacheado
    if (!_triedMusicDir) {
      String? musicDir;
      try {
        final prefs = await _prefsInstance();
        final treeUri = prefs.getString(_prefsTreeUri);
        if (treeUri != null && treeUri.isNotEmpty) {
          if (treeUri.startsWith('/')) {
            if (await Directory(treeUri).exists()) {
              musicDir = treeUri;
            } else {
              final cleaned = treeUri.replaceFirst('file://', '');
              if (await Directory(cleaned).exists()) musicDir = cleaned;
            }
          } else if (treeUri.startsWith('file://')) {
            final cleaned = treeUri.replaceFirst('file://', '');
            if (await Directory(cleaned).exists()) musicDir = cleaned;
          }
          // content:// -> no es writable como File, fallback abajo
        }
        if (musicDir == null) {
          final filePaths = prefs.getStringList(_prefsFilePaths);
          if (filePaths != null && filePaths.isNotEmpty) {
            final first = filePaths.first;
            final parent = p.dirname(first);
            if (await Directory(parent).exists()) {
              if (p.basename(parent) == _lrcFolderName) {
                musicDir = p.dirname(parent);
              } else {
                musicDir = parent;
              }
            }
          }
        }
      } catch (e) {
        _lrcLog('LRC getLrcDir resolve musicDir error: $e');
      }

      if (musicDir != null) {
        final lrcDir = Directory(p.join(musicDir, _lrcFolderName));
        try {
          if (!await lrcDir.exists()) {
            await lrcDir.create(recursive: true);
            _lrcLog('LRC dir creado: ${lrcDir.path}');
          }
          // Test escritura una sola vez (cachea resultado)
          final test = File(p.join(lrcDir.path, '.probe'));
          try {
            await test.writeAsString('ok');
            await test.delete();
            _lrcLog('LRC dir: ${lrcDir.path} (en carpeta de música)');
            _cachedDir = lrcDir;
            _triedMusicDir = true;
            return lrcDir;
          } catch (e) {
            // Scoped storage en Android 11+ impide escribir en /storage/emulated/0/...
            // No spamear logs repetidos, solo una vez
            _lrcLog('LRC probe escritura falló en ${lrcDir.path}: $e -> fallback (se cachea)');
            _triedMusicDir = true;
          }
        } catch (e) {
          _lrcLog('LRC create dir falló en ${lrcDir.path}: $e');
          _triedMusicDir = true;
        }
      } else {
        _triedMusicDir = true;
      }
    }

    // Si cache ya es fallback, retornarlo directo
    if (_cachedDir != null && await _cachedDir!.exists()) {
      return _cachedDir!;
    }

    // Fallback 2: external app-specific (sin permisos, siempre escribible)
    try {
      final ext = await getExternalStorageDirectory();
      if (ext != null) {
        final extLrc = Directory(p.join(ext.path, _lrcFolderName));
        if (!await extLrc.exists()) await extLrc.create(recursive: true);
        // Solo log la primera vez
        if (_cachedDir == null) _lrcLog('LRC dir fallback external: ${extLrc.path}');
        _cachedDir = extLrc;
        return extLrc;
      }
    } catch (_) {}

    // Fallback interno (siempre funciona)
    final appDoc = await getApplicationDocumentsDirectory();
    final fallback = Directory(p.join(appDoc.path, _lrcFolderName));
    if (!await fallback.exists()) {
      await fallback.create(recursive: true);
    }
    if (_cachedDir == null) _lrcLog('LRC dir fallback interno: ${fallback.path}');
    _cachedDir = fallback;
    return fallback;
  }

  String _sanitize(String s) {
    // Elimina caracteres no válidos para filename
    var r = s.replaceAll(RegExp(r'[\\/:*?"<>|]'), '_').trim();
    r = r.replaceAll(RegExp(r'\s+'), ' ').trim();
    if (r.isEmpty) return 'unknown';
    if (r.length > 80) r = r.substring(0, 80);
    return r;
  }

  /// Hash estable delegado en [fnv1a] (String.hashCode de Dart NO es
  /// estable entre reinicios, no sirve para nombres de fichero).
  static int stableHash(String s) => fnv1a(s);

  String _baseNameFor(SongData song) {
    // Preferencia: "Artista - Titulo" legible, con hash corto para evitar colisiones
    final display = song.displayTitle.trim();
    final artist = (song.artist ?? '').trim();
    String base;
    if (artist.isNotEmpty && display.isNotEmpty) {
      base = '${_sanitize(artist)} - ${_sanitize(display)}';
    } else if (display.isNotEmpty) {
      base = _sanitize(display);
    } else {
      final raw = song.filePath ?? song.path;
      final name = p.basenameWithoutExtension(raw);
      base = _sanitize(name);
    }
    // Hash corto estable para colisiones (mismo título/artista en archivos distintos).
    // Nota: los .lrc creados con la versión anterior (path.hashCode) quedan
    // huérfanos y se re-descargarán una sola vez con el nombre estable.
    return '${base}_${fnv1aHex(song.path)}';
  }

  Future<File> lrcFileFor(SongData song) async {
    final dir = await getLrcDir();
    final name = _baseNameFor(song);
    return File(p.join(dir.path, '$name.lrc'));
  }

  // ---- Normalización para búsqueda de letras ----

  static final _featInTitle = RegExp(
    r'\s*(?:[\(\[\{]\s*)?(?:feat\.?|ft\.?|featuring)\b[^\)\]\}]*[\)\]\}]?\s*$',
    caseSensitive: false,
  );
  static final _noiseTag = RegExp(
    r'(remaster|remastered|live|acoustic|instrumental|version|edit|mix|remix|deluxe|bonus|anniversary|mono|stereo|karaoke|demo)',
    caseSensitive: false,
  );
  static final _bracketGroup = RegExp(r'[\(\[\{][^\)\]\}]*[\)\]\}]');

  String _squash(String s) =>
      s.replaceAll(RegExp(r'\s+'), ' ').trim().replaceAll(RegExp(r'^[-–—·]+\s*|\s*[-–—·]+$'), '');

  // Quita del título: "feat. X" y tags de ruido en paréntesis/corchetes
  // (p.ej. "(Remastered 2011)", "[Live]", "- Radio Edit")
  String cleanTitle(String raw) {
    var t = raw.trim();
    t = t.replaceFirst(_featInTitle, ' ');
    var changed = true;
    var guard = 0;
    while (changed && guard++ < 10) {
      changed = false;
      for (final g in _bracketGroup.allMatches(t).toList().reversed) {
        if (_noiseTag.hasMatch(g.group(0)!)) {
          t = t.replaceRange(g.start, g.end, ' ');
          changed = true;
        }
      }
      final t2 = t.replaceFirst(
        RegExp(r'\s*[-–—]\s*(?:remaster(?:ed)?\b|live\b|\S*\s?(?:version|edit|mix)\b).*$',
            caseSensitive: false),
        ' ',
      );
      if (t2 != t) {
        t = t2;
        changed = true;
      }
    }
    final cleaned = _squash(t);
    return cleaned.isEmpty ? raw.trim() : cleaned;
  }

  // Primer artista principal: "A, B & C feat. D" -> "A".
  // Delegado en [Artists] para respetar "Tyler, The Creator" (antes lo
  // cortaba a "Tyler" y las letras fallaban para él).
  String cleanArtist(String raw) {
    return Artists.primaryArtist(raw) ?? raw.trim();
  }

  String _normForMatch(String s) =>
      s.toLowerCase().replaceAll(RegExp(r'[^a-z0-9\u4e00-\u9fff\u3040-\u30ff\uac00-\ud7af]'), '');

  bool _fuzzyContains(String haystack, String needle) {
    final h = _normForMatch(haystack);
    final n = _normForMatch(needle);
    return n.isEmpty || (h.isNotEmpty && h.contains(n));
  }

  Future<bool> hasLrc(SongData song) async {
    try {
      final f = await lrcFileFor(song);
      if (!await f.exists()) return false;
      return (await f.length()) > 0;
    } catch (_) {
      return false;
    }
  }

  /// Lista los .lrc existentes UNA vez (nombres en minúscula) para poder
  /// verificar presencia con O(1) por canción: hasLrc hace 2 awaits de I/O
  /// (exists + length) por canción, y el sync recorre la biblioteca entera
  /// dos veces (preconteo + descarga). Con 5000 temas eran ~20k stats.
  Future<Set<String>> cachedLrcNames() async {
    final names = <String>{};
    try {
      final dir = await getLrcDir();
      if (!await dir.exists()) return names;
      await for (final e in dir.list()) {
        if (e is File && e.path.toLowerCase().endsWith('.lrc')) {
          names.add(p.basename(e.path).toLowerCase());
        }
      }
    } catch (_) {}
    return names;
  }

  /// ¿[song] tiene .lrc según el set de [cachedLrcNames]?
  bool hasLrcIn(Set<String> names, SongData song) =>
      names.contains('${_baseNameFor(song).toLowerCase()}.lrc');

  /// Registra un .lrc recién creado para no re-verificarlo en disco.
  void registerLrcSaved(Set<String> names, SongData song) =>
      names.add('${_baseNameFor(song).toLowerCase()}.lrc');

  // Parser LRC básico: soporta múltiples timestamps por línea y [mm:ss.xx]
  List<LrcLine> parseLrc(String content) {
    final lines = <LrcLine>[];
    final reg = RegExp(r'\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]');
    for (final rawLine in content.split('\n')) {
      final line = rawLine.trim();
      if (line.isEmpty) continue;
      // Ignora tags tipo [ti:], [ar:], [al:], [by:], [offset:]
      if (RegExp(r'^\[(ti|ar|al|by|offset):').hasMatch(line)) continue;
      final matches = reg.allMatches(line).toList();
      if (matches.isEmpty) continue;
      final text = line.replaceAll(reg, '').trim();
      if (text.isEmpty) continue;
      for (final m in matches) {
        final min = int.tryParse(m.group(1)!) ?? 0;
        final sec = int.tryParse(m.group(2)!) ?? 0;
        final fracStr = m.group(3) ?? '0';
        int millis = 0;
        if (fracStr.length == 1) {
          millis = int.parse(fracStr) * 100;
        } else if (fracStr.length == 2) {
          millis = int.parse(fracStr) * 10;
        } else if (fracStr.length == 3) {
          millis = int.parse(fracStr);
        }
        final ts = Duration(minutes: min, seconds: sec, milliseconds: millis);
        lines.add(LrcLine(ts, text));
      }
    }
    lines.sort((a, b) => a.timestamp.compareTo(b.timestamp));
    return lines;
  }

  Future<List<LrcLine>?> loadLrc(SongData song) async {
    try {
      final file = await lrcFileFor(song);
      if (!await file.exists()) return null;
      final content = await file.readAsString();
      if (content.trim().isEmpty) return null;
      final parsed = parseLrc(content);
      if (parsed.isEmpty) {
        // Puede ser plain lyrics sin timestamps -> devolver como líneas sin sync (timestamp 0)
        final plainLines = content.split('\n').where((l) => l.trim().isNotEmpty).map((l) => LrcLine(Duration.zero, l.trim())).toList();
        return plainLines.isEmpty ? null : plainLines;
      }
      return parsed;
    } catch (_) {
      return null;
    }
  }

  // Intenta obtener LRC de lrclib.net (gratis, sin key)
  // 1) /api/get con artist, track, album, duration (solo si hay artista)
  // 2) fallback /api/search
  Future<String?> fetchSyncedLyrics(SongData song) async {
    final artist = (song.artist ?? '').trim();
    final title = song.displayTitle.trim();
    if (title.isEmpty) {
      _lrcLog('LRC fetch skip: title vacío para ${song.path}');
      return null;
    }
    final album = (song.album ?? '').trim();
    final durationSec = song.durationMs != null ? (song.durationMs! / 1000).round() : null;

    // Helper para request
    Future<String?> tryGet(Map<String, String> params) async {
      try {
        final uri = Uri.https('lrclib.net', '/api/get', params);
        _lrcLog('LRC tryGet: $uri');
        final res = await _lrcRequest(
          () => http.get(uri, headers: {'User-Agent': '8th/1.0'}),
          label: 'LRC tryGet',
        );
        if (res == null) return null;
        _lrcLog('LRC tryGet status ${res.statusCode} for $title / $artist');
        if (res.statusCode == 200) {
          final body = res.body;
          final synced = _extractJsonField(body, 'syncedLyrics');
          if (synced != null && synced.trim().isNotEmpty) {
            _lrcLog('LRC tryGet synced encontrado para $title');
            return synced;
          }
          final plain = _extractJsonField(body, 'plainLyrics');
          if (plain != null && plain.trim().isNotEmpty) {
            _lrcLog('LRC tryGet plain encontrado para $title');
            return plain;
          }
          _lrcLog('LRC tryGet sin letras para $title');
        } else {
          _lrcLog('LRC tryGet no 200: ${_preview(res.body)}');
        }
      } catch (e) {
        _lrcLog('LRC tryGet error $title: $e');
      }
      return null;
    }

    Future<String?> trySearchParams(String artistName, String trackName) async {
      try {
        final q = [if (artistName.isNotEmpty) artistName, trackName].join(' ').trim();
        final uri = Uri.https('lrclib.net', '/api/search', {
          'q': q,
          if (artistName.isNotEmpty) 'artist_name': artistName,
          if (trackName.isNotEmpty) 'track_name': trackName,
        });
        _lrcLog('LRC trySearch: $uri');
        final res = await _lrcRequest(
          () => http.get(uri, headers: {'User-Agent': '8th/1.0'}),
          label: 'LRC trySearch',
        );
        if (res == null) return null;
        _lrcLog('LRC trySearch status ${res.statusCode} for $q');
        if (res.statusCode == 200) {
          final body = res.body;
          final decoded = _tryJsonDecode(body);
          if (decoded is List && decoded.isNotEmpty) {
            _lrcLog('LRC search encontrados ${decoded.length} para $q');
            final items = decoded.whereType<Map>().toList();
            bool durOk(Map it) {
              final d = (it['duration'] as num?)?.toInt();
              return durationSec == null || d == null || (d - durationSec).abs() <= 3;
            }

            // En modo solo-título exigimos duración aproximada para no coger otra canción
            final candidates = (artistName.isEmpty && durationSec != null)
                ? items.where(durOk).toList()
                : items;
            for (final item in candidates) {
              final synced = item['syncedLyrics'] as String?;
              if (synced != null && synced.trim().isNotEmpty) {
                _lrcLog('LRC search synced encontrado para $q');
                return synced;
              }
            }
            for (final item in candidates) {
              final plain = item['plainLyrics'] as String?;
              if (plain != null && plain.trim().isNotEmpty) {
                _lrcLog('LRC search plain encontrado para $q');
                return plain;
              }
            }
          } else {
            _lrcLog('LRC search vacío para $q');
          }
        }
      } catch (e) {
        _lrcLog('LRC trySearch error $title: $e');
      }
      return null;
    }

    // 1) Intento exacto solo si tenemos artista (lrclib /get requiere ambos)
    if (artist.isNotEmpty) {
      final getParams = <String, String>{
        'artist_name': artist,
        'track_name': title,
        if (album.isNotEmpty) 'album_name': album,
        if (durationSec != null) 'duration': durationSec.toString(),
      };
      final r = await tryGet(getParams);
      if (r != null) return r;
      // Intento sin duration/album
      final r2 = await tryGet({'artist_name': artist, 'track_name': title});
      if (r2 != null) return r2;
    } else {
      _lrcLog('LRC skip get por falta de artista para "$title", directo a search');
    }

    // 2) Intento exacto con título/artista normalizados (sin remaster, feat, etc.)
    final normTitle = cleanTitle(title);
    final normArtist = artist.isEmpty ? '' : cleanArtist(artist);
    if (normArtist.isNotEmpty && (normTitle != title || normArtist != artist)) {
      final r3 = await tryGet({'artist_name': normArtist, 'track_name': normTitle});
      if (r3 != null) return r3;
    }

    // 3) Búsqueda con datos crudos
    final s = await trySearchParams(artist, title);
    if (s != null) return s;

    // 4) Búsqueda con datos normalizados
    if (normTitle != title || (normArtist.isNotEmpty && normArtist != artist)) {
      final s2 = await trySearchParams(normArtist, normTitle);
      if (s2 != null) return s2;

      // 5) Búsqueda solo por título (para artistas mal etiquetados)
      final s3 = await trySearchParams('', normTitle);
      if (s3 != null) return s3;
    }

    // 6) NetEase (LRC sincronizada, buena cobertura internacional)
    final ne = await _fetchFromNetease(normArtist, normTitle, durationSec);
    if (ne != null) return ne;

    // 7) Fallback secundario: lyrics.ovh (gratis, sin key, plain)
    final ovh = await _fetchFromLyricsOvh(song);
    if (ovh != null) return ovh;

    return null;
  }

  // NetEase Cloud Music: búsqueda + letra (LRC sincronizada si existe, gratis sin key)
  Future<String?> _fetchFromNetease(String artist, String title, int? durationSec) async {
    if (title.isEmpty) return null;
    const headers = {
      'User-Agent': 'Mozilla/5.0 (X11; Linux x86_64) 8th/1.0',
      'Referer': 'https://music.163.com',
    };
    try {
      final q = [artist, title].where((s) => s.isNotEmpty).join(' ');
      final searchRes = await _lrcRequest(
        () => http.post(
          Uri.parse('https://music.163.com/api/search/get'),
          body: {'s': q, 'type': '1', 'limit': '10', 'offset': '0'},
          headers: headers,
        ),
        label: 'LRC netease search',
      );
      if (searchRes == null) return null;
      _lrcLog('LRC netease search status ${searchRes.statusCode} for $q');
      if (searchRes.statusCode != 200) return null;
      final decoded = _tryJsonDecode(searchRes.body);
      final songs = (decoded is Map ? decoded['result'] : null)?['songs'];
      if (songs is! List || songs.isEmpty) {
        _lrcLog('LRC netease sin resultados para $q');
        return null;
      }
      Map? best;
      int bestScore = -1;
      for (final s in songs) {
        if (s is! Map) continue;
        final name = (s['name'] ?? '').toString();
        final artists = ((s['artists'] as List?) ?? const [])
            .whereType<Map>()
            .map((a) => (a['name'] ?? '').toString())
            .join(', ');
        if (!_fuzzyContains(name, title)) continue;
        if (artist.isNotEmpty && !_fuzzyContains(artists, artist)) continue;
        int score = 0;
        final dMs = (s['duration'] as num?)?.toInt();
        if (durationSec != null && dMs != null) {
          final diff = ((dMs / 1000) - durationSec).abs();
          if (diff <= 3) {
            score += 2;
          } else if (diff <= 8) {
            score += 1;
          }
        }
        if (score > bestScore) {
          bestScore = score;
          best = s;
        }
      }
      if (best == null) {
        _lrcLog('LRC netease sin match para "$title" / "$artist"');
        return null;
      }
      final id = best['id'];
      final lyricRes = await _lrcRequest(
        () => http.get(
          Uri.parse(
              'https://music.163.com/api/song/lyric?id=$id&lv=1&kv=1&tv=-1'),
          headers: headers,
        ),
        label: 'LRC netease lyric',
      );
      if (lyricRes == null) return null;
      _lrcLog('LRC netease lyric status ${lyricRes.statusCode} id=$id');
      if (lyricRes.statusCode != 200) return null;
      final ldecoded = _tryJsonDecode(lyricRes.body);
      String? lyric;
      if (ldecoded is Map) {
        final lrc = ldecoded['lrc'];
        if (lrc is Map && lrc['lyric'] is String) lyric = lrc['lyric'] as String;
      }
      if (lyric != null) {
        final trimmed = lyric.trim();
        // "纯音乐，请欣赏" = pista instrumental, no es letra
        if (trimmed.isNotEmpty && !trimmed.contains('纯音乐')) {
          _lrcLog('LRC netease letra encontrada id=$id');
          return trimmed;
        }
      }
    } catch (e) {
      _lrcLog('LRC netease error $title: $e');
    }
    return null;
  }

  Future<String?> _fetchFromLyricsOvh(SongData song) async {
    final rawArtist = (song.artist ?? '').trim();
    final title = song.displayTitle.trim();
    if (rawArtist.isEmpty || title.isEmpty) return null;
    final artist = cleanArtist(rawArtist);
    if (artist.isEmpty) return null;
    try {
      final uri = Uri.parse('https://api.lyrics.ovh/v1/${Uri.encodeComponent(artist)}/${Uri.encodeComponent(title)}');
      _lrcLog('LRC ovh try: $uri');
      final res = await _lrcRequest(
        () => http.get(uri, headers: {'User-Agent': '8th/1.0'}),
        label: 'LRC ovh',
      );
      if (res == null) return null;
      _lrcLog('LRC ovh status ${res.statusCode} for $title / $artist');
      if (res.statusCode == 200) {
        final decoded = _tryJsonDecode(res.body);
        if (decoded is Map && decoded['lyrics'] is String) {
          final lyrics = (decoded['lyrics'] as String).trim();
          if (lyrics.isNotEmpty) {
            _lrcLog('LRC ovh plain encontrado para $title');
            return lyrics;
          }
        }
      } else {
        _lrcLog('LRC ovh no 200: ${_preview(res.body, 150)}');
      }
    } catch (e) {
      _lrcLog('LRC ovh error $title: $e');
    }
    return null;
  }

  dynamic _tryJsonDecode(String body) {
    try {
      return jsonDecode(body);
    } catch (_) {
      return null;
    }
  }

  String? _extractJsonField(String body, String field) {
    try {
      final decoded = _tryJsonDecode(body);
      if (decoded is Map && decoded[field] is String) {
        return decoded[field] as String;
      }
    } catch (_) {}
    // fallback regex: desescapa vía jsonDecode para no romper con
    // secuencias (\n, \", \\, \uXXXX). El replaceAll manual anterior
    // corrompía barras y saltos en letras con comillas.
    try {
      final reg = RegExp('"$field"\\s*:\\s*"((?:\\\\.|[^"\\\\])*)"', dotAll: true);
      final m = reg.firstMatch(body);
      if (m != null) {
        final raw = m.group(1) ?? '';
        final unescaped = _tryJsonDecode('"$raw"');
        if (unescaped is String) return unescaped;
        return raw;
      }
    } catch (_) {}
    return null;
  }

  static String _preview(String body, [int max = 200]) {
    if (body.length <= max) return body;
    return body.substring(0, max);
  }

  Future<bool> fetchAndSave(SongData song) async {
    try {
      if (await hasLrc(song)) return true;
      final content = await fetchSyncedLyrics(song);
      if (content == null || content.trim().isEmpty) {
        _lrcLog('LRC no encontrado para ${song.displayTitle}');
        return false;
      }
      final file = await lrcFileFor(song);
      await file.writeAsString(content, flush: true);
      _lrcLog('LRC guardado: ${file.path}');
      return true;
    } catch (e) {
      _lrcLog('Error fetch LRC ${song.displayTitle}: $e');
      return false;
    }
  }

  /// Borra .lrc vacíos (descargas fallidas a medias). Higiene automática
  /// al final de cada sync; no toca letras válidas ni de otras bibliotecas.
  Future<int> purgeEmptyLrc() async {
    try {
      final dir = await getLrcDir();
      if (!await dir.exists()) return 0;
      var n = 0;
      await for (final e in dir.list()) {
        if (e is! File || !e.path.toLowerCase().endsWith('.lrc')) continue;
        try {
          if (await e.length() == 0) {
            await e.delete();
            n++;
          }
        } catch (_) {}
      }
      return n;
    } catch (_) {
      return 0;
    }
  }

  // Sincroniza faltantes: compara nº canciones vs nº .lrc + verificación individual
  // Descarga secuencial con throttle para no saturar lrclib (2 concurrent max, 300ms entre requests)
  // Retorna nº descargadas para UI. Cancelable vía [shouldCancel] y con
  // corte temprano si la red falla en serie (antes seguía cientos de temas
  // con timeouts de 10s+reintentos aunque no hubiera internet).
  Future<int> syncMissing(
    List<SongData> songs, {
    void Function(int done, int total)? onProgress,
    bool Function()? shouldCancel,
  }) async {
    if (songs.isEmpty) return 0;
    final total = songs.length;
    // Un solo listado del directorio para TODAS las verificaciones.
    final lrcNames = await cachedLrcNames();
    _lrcLog('LRC sync: canciones=$total, lrcs=${lrcNames.length}');

    int done = 0;
    int fetched = 0;
    int consecutiveMisses = 0;
    const throttle = Duration(milliseconds: 350);

    for (final song in songs) {
      if (shouldCancel?.call() ?? false) {
        _lrcLog('LRC sync cancelado en $done/$total');
        break;
      }
      done++;
      onProgress?.call(done, total);
      if (hasLrcIn(lrcNames, song)) continue;
      final ok = await fetchAndSave(song);
      if (ok) {
        fetched++;
        consecutiveMisses = 0;
        // Ya está en disco: no re-verificar ni re-contar como pendiente.
        registerLrcSaved(lrcNames, song);
      } else {
        consecutiveMisses++;
        // Sin red o rate-limit sostenido: aborta para no quemar batería
        // con cientos de timeouts. El usuario puede reintentar luego.
        if (consecutiveMisses >= 15 && fetched == 0) {
          _lrcLog('LRC sync abortado: $consecutiveMisses fallos seguidos sin éxito');
          break;
        }
      }
      // throttle solo si se hizo request (para no spam)
      await Future.delayed(throttle);
      // Ceder a UI cada 10
      if (done % 10 == 0) {
        await Future.delayed(const Duration(milliseconds: 10));
      }
    }
    _lrcLog('LRC sync terminado: fetched=$fetched, total lrcs=${lrcNames.length + fetched}');
    try {
      await purgeEmptyLrc();
    } catch (_) {}
    return fetched;
  }
}


