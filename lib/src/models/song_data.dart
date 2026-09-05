import 'dart:io';
import 'dart:typed_data';

import 'package:audio_metadata_reader/audio_metadata_reader.dart';
import 'package:audio_service/audio_service.dart' show MediaItem;
import 'package:flutter/foundation.dart';

import '../utils/audio_formats.dart';
import '../utils/metadata_fixes.dart';

class SongData {
  final String path;
  final String? contentUri;
  final String? filePath;
  final String? title;
  final String? artist;
  final String? album;
  final int? trackNumber;
  final int? durationMs;
  final String? albumArtUri;
  Uint8List? albumArt;

  SongData({
    required this.path,
    this.contentUri,
    this.filePath,
    this.title,
    this.artist,
    this.album,
    this.trackNumber,
    this.durationMs,
    this.albumArtUri,
    this.albumArt,
  });

  String get playUri => (contentUri != null && contentUri!.isNotEmpty) ? contentUri! : path;

  String get displayTitle {
    if (title != null && title!.trim().isNotEmpty) return title!.trim();
    // Para content:// sin metadatos el último segmento es un ID numérico
    // (p.ej. .../123): prefiere filePath o el nombre MediaStore antes que eso.
    final candidates = [filePath, path];
    for (final c in candidates) {
      if (c == null || c.isEmpty) continue;
      if (c.startsWith('content://')) continue;
      final base = c.split('/').last.replaceAll(RegExp(r'\.[^.]+$'), '');
      if (base.isNotEmpty && int.tryParse(base) == null) return base;
    }
    // Último recurso: si todo es content://, evita mostrar solo el ID.
    final fallback = path.split('/').last.replaceAll(RegExp(r'\.[^.]+$'), '');
    if (fallback.isEmpty || int.tryParse(fallback) != null) {
      return AppStrings.untitled;
    }
    return fallback;
  }

  bool get isContentUri => playUri.startsWith('content://');

  /// contentUri primero (estable ante movimientos), filePath después.
  /// Sin existsSync: este getter se llama en el hilo UI por cada tema al
  /// armar la cola. Si el fichero falta, just_audio lo reporta y
  /// playQueueList ya lo captura; bloquear la UI es peor.
  Uri get playbackUri {
    if (isContentUri) return Uri.parse(playUri);
    if (filePath != null && filePath!.isNotEmpty && !filePath!.startsWith('content://')) {
      return Uri.file(filePath!);
    }
    if (path.startsWith('content://')) return Uri.parse(path);
    return Uri.file(path);
  }

  /// Identificador estable para Hero/cache: prefiere contentUri.
  String get stableId => playUri;

  /// Clave estable por entrada de cola: permite duplicados (mismo tema 2x
  /// en cola) sin depender del índice. Con `playUri_$index` como clave, un
  /// reordenado cambiaba TODAS las claves y reconstruía cada fila visible.
  /// El nº de ocurrencia previa mantiene la clave de cada repetición
  /// estable aunque cambie su posición absoluta.
  static String queueEntryKey(List<SongData> list, int index) {
    final uri = list[index].playUri;
    var occurrence = 0;
    for (var i = 0; i < index; i++) {
      if (list[i].playUri == uri) occurrence++;
    }
    return '$uri#$occurrence';
  }

  /// Reconstruye un SongData desde el MediaItem del player (se usaba
  /// duplicado en lyrics_view y lyrics_full_screen para seguir el tema
  /// actual desde el sequence de just_audio).
  factory SongData.fromMediaItem(MediaItem tag, {Uint8List? albumArt}) {
    return SongData(
      path: tag.id,
      contentUri: tag.id,
      title: tag.title,
      artist: tag.artist,
      album: tag.album,
      durationMs: tag.duration?.inMilliseconds,
      albumArt: albumArt,
    );
  }
}

class QueueState {
  List<SongData> list;
  int index;
  String label;

  QueueState({required this.list, required this.index, required this.label});
}

enum ViewTab { albums, queue }

List<SongData> parseMetadata(List<String> paths) {
  // Corre en isolate (compute): aquí sí vale existsSync.
  final songs = <SongData>[];
  for (final path in paths) {
    // content:// de MediaStore/SAF no son ficheros: no filtrar por
    // extensión ni por existsSync, van directos.
    if (path.startsWith('content://')) {
      songs.add(SongData(path: path, contentUri: path));
      continue;
    }
    final ext = path.split('.').last.toLowerCase();
    if (!audioExtensions.contains(ext)) continue;
    try {
      final file = File(path);
      // Sin fantasma: si no existe, se filtra (antes se añadía un
      // SongData vacío que luego fallaba en playQueueList).
      if (!file.existsSync()) continue;
      // Sin getImage aquí: retener el arte por canción en memoria
      // (~500KB x N temas = OOM). Las portadas llegan por
      // enrichAlbumArt / caché en disco.
      final meta = readMetadata(file, getImage: false);
      songs.add(SongData(
        path: path,
        title: meta.title,
        artist: MetadataFixes.artistFor(album: meta.album, artist: meta.artist),
        album: meta.album,
        trackNumber: meta.trackNumber,
        durationMs: meta.duration?.inMilliseconds,
        albumArt: null,
      ));
    } catch (_) {
      // Existe pero ilegible: conserva entrada mínima para intentarlo
      // en reproducción (playQueueList ya cuenta fallidos).
      try {
        if (File(path).existsSync()) {
          songs.add(SongData(path: path));
        }
      } catch (_) {}
    }
  }
  return songs;
}
