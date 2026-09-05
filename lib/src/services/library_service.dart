import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:file_picker/file_picker.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/song_data.dart';
import '../utils/artists.dart';
import '../utils/audio_formats.dart' as fmt;
import '../utils/hashing.dart';
import '../utils/metadata_fixes.dart';

class LibraryService {
  static const _channel = MethodChannel('com.xly.u_8th/files');
  static const _prefsTreeUri = 'tree_uri';
  static const _prefsFilePaths = 'file_paths';
  static const _prefsAlbumOrder = 'album_order';
  static const _unknown = fmt.AppStrings.unknown;

  Directory? _artCacheDir;
  SharedPreferences? _prefs;

  /// Instancia cacheada: antes cada getter/setter pedía
  /// SharedPreferences.getInstance() de nuevo (8 llamadas por arranque).
  Future<SharedPreferences> _prefsInstance() async =>
      _prefs ??= await SharedPreferences.getInstance();

  // audioExtensions se importa de utils/audio_formats.dart (fuente única,
  // compartida con parseMetadata).

  // ---- Caché de portadas en disco (para que aparezcan al instante al abrir) ----

  Future<Directory?> _getArtCacheDir() async {
    if (_artCacheDir != null) return _artCacheDir;
    try {
      final base = await getApplicationSupportDirectory();
      final dir = Directory(p.join(base.path, 'art_cache'));
      if (!dir.existsSync()) await dir.create(recursive: true);
      _artCacheDir = dir;
      return dir;
    } catch (_) {
      return null;
    }
  }

  static int _stableHash(String s) => fnv1a(s);

  String _albumKeyOld(String album) =>
      album.toLowerCase().trim().replaceAll(RegExp(r'[^a-z0-9]+'), '_');

  /// Clave estable con artista para evitar colisiones ("Greatest Hits" de
  /// dos artistas distintos). Incluye fallback al formato antiguo en lectura.
  String _albumKey(String album, {String? artist}) {
    final a = album.toLowerCase().trim();
    final ar = (artist ?? '').toLowerCase().trim();
    final combined = ar.isEmpty ? a : '$ar\x00$a';
    final safe = combined.replaceAll(RegExp(r'[^a-z0-9]+'), '_');
    final trimmed = safe.length > 60 ? safe.substring(0, 60) : safe;
    final hash =
        _stableHash(combined).toRadixString(16).padLeft(8, '0').substring(0, 6);
    return '${trimmed}_$hash';
  }

  Future<void> cacheAlbumArt(String album, Uint8List bytes,
      {String? artist}) async {
    try {
      final dir = await _getArtCacheDir();
      if (dir == null || bytes.isEmpty) return;
      final f =
          File(p.join(dir.path, '${_albumKey(album, artist: artist)}.img'));
      await f.writeAsBytes(bytes, flush: true);
    } catch (_) {}
  }

  Future<Uint8List?> loadCachedAlbumArt(String album,
      {String? artist}) async {
    try {
      final dir = await _getArtCacheDir();
      if (dir == null) return null;
      final f =
          File(p.join(dir.path, '${_albumKey(album, artist: artist)}.img'));
      if (f.existsSync()) {
        final bytes = await f.readAsBytes();
        if (bytes.isNotEmpty) return bytes;
      }
      // Migración: prueba el formato antiguo (solo álbum) una vez.
      final legacy =
          File(p.join(dir.path, '${_albumKeyOld(album)}.img'));
      if (legacy.existsSync()) {
        final bytes = await legacy.readAsBytes();
        return bytes.isEmpty ? null : bytes;
      }
      return null;
    } catch (_) {
      return null;
    }
  }

  /// Borra la caché de portadas en disco (se regenera sola al hacer scroll).
  /// Devuelve nº de ficheros borrados. Llamado desde el diálogo de info.
  Future<int> clearArtCache() async {
    try {
      final dir = await _getArtCacheDir();
      if (dir == null || !dir.existsSync()) return 0;
      var n = 0;
      await for (final e in dir.list()) {
        if (e is! File) continue;
        try {
          await e.delete();
          n++;
        } catch (_) {}
      }
      return n;
    } catch (_) {
      return 0;
    }
  }

  Future<bool> ensureAudioPermission() async {
    if (!Platform.isAndroid) return true;
    try {
      // Permission.audio solo existe en API 33+: en API 29-32 hace falta
      // READ_EXTERNAL_STORAGE (Permission.storage) o nunca concede.
      var status = await Permission.audio.status;
      if (!status.isGranted) {
        status = await Permission.audio.request();
      }
      if (status.isGranted) return true;
      var storage = await Permission.storage.status;
      if (!storage.isGranted) {
        storage = await Permission.storage.request();
      }
      return storage.isGranted;
    } catch (_) {
      return true;
    }
  }

  Future<void> ensureNotificationPermission() async {
    if (!Platform.isAndroid) return;
    try {
      final status = await Permission.notification.status;
      if (!status.isGranted) {
        await Permission.notification.request();
      }
    } catch (_) {}
  }

  Future<String?> pickFolder() async {
    await ensureAudioPermission();
    final result = await FilePicker.getDirectoryPath(
      dialogTitle: 'Selecciona carpeta de música',
    );
    if (result == null) return null;
    final prefs = await _prefsInstance();
    await prefs.setString(_prefsTreeUri, result);
    // Limpia el modo alternativo para no dejar prefs huérfanas (antes
    // quedaban filePaths + treeUri a la vez y treeUri ganaba en silencio).
    await prefs.remove(_prefsFilePaths);
    return result;
  }

  // Extensiones compartidas con parseMetadata (fuente única en utils).
  static const audioExtensions = fmt.audioExtensions;

  /// Escaneo directo en Dart (fallback cuando MediaStore/DATA falla en
  /// Android 10+ por scoped storage o columna DATA deprecada). Corre en
  /// background: no bloquear el hilo UI con bibliotecas grandes.
  Future<List<String>> scanDirectoryForAudio(String dirPath) async {
    final dir = Directory(dirPath);
    if (!await dir.exists()) return [];
    final paths = <String>[];
    try {
      await for (final entity
          in dir.list(recursive: true, followLinks: false)) {
        if (entity is! File) continue;
        final ext =
            entity.path.split('.').last.toLowerCase();
        if (audioExtensions.contains(ext)) paths.add(entity.path);
      }
    } catch (_) {}
    paths.sort();
    return paths;
  }

  Future<List<String>?> pickFiles() async {
    final files = await FilePicker.pickFiles(
      type: FileType.custom,
      allowedExtensions: ['mp3', 'wav', 'flac', 'aac', 'm4a', 'ogg', 'wma', 'opus'],
      dialogTitle: 'Selecciona archivos de música',
    );
    // file_picker 12 devuelve lista vacía al cancelar (no null).
    if (files.isEmpty) return null;
    // Sin existsSync aquí (bloquea UI): loadSongsIsolate ya filtra en
    // background los paths inexistentes. En Android SAF el path puede ser
    // null: si ninguno tiene path, trátalo como cancelación.
    final paths = files
        .map((f) => f.path)
        .whereType<String>()
        .where((p) => p.isNotEmpty)
        .toList();
    if (paths.isEmpty) return null;
    final prefs = await _prefsInstance();
    await prefs.remove(_prefsTreeUri);
    await prefs.setStringList(_prefsFilePaths, paths);
    return paths;
  }

  Future<List<Map<dynamic, dynamic>>?> listAudioFiles(String treeUri) async {
    try {
      final fileList = await _channel.invokeListMethod<Map<dynamic, dynamic>>(
        'listAudioFiles',
        {'treeUri': treeUri},
      );
      return fileList;
    } catch (_) {
      return null;
    }
  }

  Future<Uint8List?> extractArt(SongData song) async {
    try {
      final res = await _channel.invokeMethod<Uint8List>('extractArt', {
        'path': song.filePath,
        'uri': song.isContentUri ? song.contentUri : null,
        'artUri': song.albumArtUri,
      });
      if (res == null || res.isEmpty) return null;
      return res;
    } catch (_) {
      return null;
    }
  }

  List<SongData> fromRichList(List<Map<dynamic, dynamic>> fileList) {
    final songs = <SongData>[];
    for (final m in fileList) {
      final uri = m['uri'] as String? ?? '';
      if (uri.isEmpty) continue;
      final name = m['name'] as String? ?? uri.split('/').last;
      final title = m['title'] as String?;
      final artist = m['artist'] as String?;
      final album = m['album'] as String?;
      final trackRaw = m['track'];
      final nativePath = m['path'] as String?;
      final artUri = m['artUri'] as String?;
      final durationRaw = m['duration'];
      int? track;
      if (trackRaw is int) {
        track = trackRaw > 0 ? trackRaw : null;
      } else if (trackRaw is String) {
        track = int.tryParse(trackRaw.split('/').first);
      }
      final duration = durationRaw is num ? durationRaw.toInt() : null;
      songs.add(SongData(
        path: uri,
        contentUri: uri,
        filePath: nativePath,
        title: title ?? name.replaceAll(RegExp(r'\.[^.]+$'), ''),
        artist: MetadataFixes.artistFor(album: album, artist: artist),
        album: album,
        trackNumber: track,
        durationMs: (duration != null && duration > 0) ? duration : null,
        albumArtUri: (artUri != null && artUri.isNotEmpty) ? artUri : null,
        albumArt: null,
      ));
    }
    return songs;
  }

  Future<List<SongData>> loadSongsIsolate(List<String> paths) async {
    try {
      return await compute(parseMetadata, paths);
    } catch (_) {
      return paths.map((p) => SongData(path: p)).toList();
    }
  }

  Future<List<String>> getAlbumOrder() async {
    final prefs = await _prefsInstance();
    return prefs.getStringList(_prefsAlbumOrder) ?? [];
  }

  Future<void> saveAlbumOrder(List<String> order) async {
    final prefs = await _prefsInstance();
    await prefs.setStringList(_prefsAlbumOrder, order);
  }

  Future<String?> getTreeUri() async {
    final prefs = await _prefsInstance();
    return prefs.getString(_prefsTreeUri);
  }

  Future<List<String>?> getFilePaths() async {
    final prefs = await _prefsInstance();
    return prefs.getStringList(_prefsFilePaths);
  }

  Future<void> saveFilePaths(List<String> paths) async {
    final prefs = await _prefsInstance();
    await prefs.setStringList(_prefsFilePaths, paths);
  }

  Future<void> clearTreeUri() async {
    final prefs = await _prefsInstance();
    await prefs.remove(_prefsTreeUri);
  }

  // Pure helpers for grouping/sorting
  int resolveTrack(SongData s) {
    if (s.trackNumber != null) return s.trackNumber!;
    final t = s.title ?? s.displayTitle;
    final m = RegExp(r'^(\d+)').firstMatch(t);
    if (m != null) return int.tryParse(m.group(1)!) ?? 999;
    return 999;
  }

  Map<String, List<SongData>> groupByAlbum(List<SongData> songs) {
    // Paso 1: agrupa por nombre de álbum normalizado (insensible a
    // mayúsculas/espacios: "Abbey Road" y "abbey road" son el mismo).
    final byName = <String, List<SongData>>{};
    final displayName = <String, String>{};
    for (final s in songs) {
      final raw = (s.album?.trim().isNotEmpty ?? false) ? s.album!.trim() : _unknown;
      final key = raw.toLowerCase();
      byName.putIfAbsent(key, () => []);
      byName[key]!.add(s);
      displayName.putIfAbsent(key, () => raw);
    }
    // Paso 2: si el mismo nombre lo usan artistas distintos ("Greatest
    // Hits" de Queen y de ABBA), divide por artista principal. Los feats
    // ("Eminem" + "Eminem feat. Rihanna") comparten principal y NO dividen.
    final map = <String, List<SongData>>{};
    void emit(String visible, List<SongData> tracks) {
      var unique = visible;
      var i = 2;
      while (map.containsKey(unique)) {
        unique = '$visible ($i)';
        i++;
      }
      map[unique] = tracks;
    }

    for (final entry in byName.entries) {
      final albumDisplay = displayName[entry.key]!;
      final tracks = entry.value;
      if (albumDisplay == _unknown) {
        emit(_unknown, tracks);
        continue;
      }
      // Principal por tema (minúsculas; '' si no hay artista).
      final principals = <String>[];
      final principalDisplay = <String, String>{};
      for (final t in tracks) {
        final p = Artists.primaryArtist(t.artist);
        final key = p == null ? '' : p.toLowerCase();
        principals.add(key);
        if (key.isNotEmpty) principalDisplay.putIfAbsent(key, () => p!);
      }
      final distinct = principals.where((p) => p.isNotEmpty).toSet();
      if (distinct.length <= 1) {
        emit(albumDisplay, tracks);
        continue;
      }
      // ¿Hay un principal dominante (mayoría)? Entonces es el mismo álbum
      // con colaboraciones, no dos álbumes distintos.
      final counts = <String, int>{};
      for (final p in principals) {
        if (p.isEmpty) continue;
        counts[p] = (counts[p] ?? 0) + 1;
      }
      var dominant = '';
      var dominantCount = 0;
      counts.forEach((k, v) {
        if (v > dominantCount) {
          dominant = k;
          dominantCount = v;
        }
      });
      final withPrincipal = counts.values.fold(0, (a, b) => a + b);
      // Mayoría estricta (>50%): un empate 1-1 son dos álbumes distintos
      // con el mismo nombre y debe dividir.
      if (dominant.isNotEmpty && dominantCount * 2 > withPrincipal) {
        emit(albumDisplay, tracks);
        continue;
      }
      // Artistas realmente distintos con el mismo nombre: divide.
      final buckets = <String, List<SongData>>{};
      for (var i = 0; i < tracks.length; i++) {
        final p = principals[i];
        buckets.putIfAbsent(p, () => []);
        buckets[p]!.add(tracks[i]);
      }
      // Los temas sin artista van al grupo mayoritario.
      final noArtist = buckets.remove('') ?? const <SongData>[];
      final orderedKeys = buckets.keys.toList()
        ..sort((a, b) => buckets[b]!.length.compareTo(buckets[a]!.length));
      for (var i = 0; i < orderedKeys.length; i++) {
        final k = orderedKeys[i];
        final bucket = buckets[k]!;
        final group = (i == 0 && noArtist.isNotEmpty)
            ? [...bucket, ...noArtist]
            : bucket;
        emit('$albumDisplay — ${principalDisplay[k] ?? k}', group);
      }
      if (orderedKeys.isEmpty && noArtist.isNotEmpty) {
        emit(albumDisplay, List<SongData>.from(noArtist));
      }
    }
    for (final key in map.keys) {
      map[key]!.sort((a, b) {
        final tA = resolveTrack(a);
        final tB = resolveTrack(b);
        final cmp = tA.compareTo(tB);
        if (cmp != 0) return cmp;
        return a.displayTitle.toLowerCase().compareTo(b.displayTitle.toLowerCase());
      });
    }
    final sortedKeys = map.keys.toList()..sort((a, b) {
      if (a == _unknown) return 1;
      if (b == _unknown) return -1;
      return a.toLowerCase().compareTo(b.toLowerCase());
    });
    final result = <String, List<SongData>>{};
    for (final key in sortedKeys) {
      result[key] = map[key]!;
    }
    return result;
  }

  List<String> sortedAlbumKeys(Map<String, List<SongData>> grouped, List<String> albumOrder) {
    // O(n): antes contains+remove en lista era O(n²) con 1000 álbumes.
    final remaining = Set<String>.of(grouped.keys);
    final ordered = <String>[];
    for (final a in albumOrder) {
      if (remaining.remove(a)) ordered.add(a);
    }
    // Mantiene el orden de inserción de grouped para el resto.
    for (final k in grouped.keys) {
      if (remaining.contains(k)) ordered.add(k);
    }
    return ordered;
  }
}
