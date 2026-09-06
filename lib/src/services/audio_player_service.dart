import 'dart:async';
import 'dart:io';
import 'dart:math';

import 'package:audio_service/audio_service.dart';
import 'package:flutter/foundation.dart';
import 'package:just_audio/just_audio.dart';
import 'package:path_provider/path_provider.dart';

import '../models/song_data.dart';
import '../utils/audio_formats.dart';
import '../utils/hashing.dart';

/// MediaSession: notificación con controles, control desde el sistema
/// (bloqueo, bluetooth, auriculares) y servicio en primer plano mientras
/// se reproduce (evita que el sistema cierre la app en segundo plano).
class EighthAudioHandler extends BaseAudioHandler with SeekHandler {
  final AudioPlayer player;
  /// Rutas de salto robustas (toques rápidos) inyectadas por el servicio;
  /// si no están asignadas, se usa el seek directo del player.
  void Function()? onSkipNext;
  void Function()? onSkipPrevious;
  // ignore: unused_field - kept for future dispose if handler lifecycle changes
  late final List<StreamSubscription> _subs;

  EighthAudioHandler(this.player) {
    _subs = [
      player.playbackEventStream.listen(_broadcastState),
      player.sequenceStateStream.listen((state) {
        final tag = state.currentSource?.tag;
        if (tag is MediaItem) {
          mediaItem.add(tag);
        }
      }),
    ];
  }

  Future<void> disposeHandler() async {
    for (final s in _subs) {
      try {
        await s.cancel();
      } catch (_) {}
    }
  }

  void setQueueItems(List<MediaItem> items) {
    queue.add(items);
  }

  void _broadcastState(PlaybackEvent event) {
    final playing = player.playing;
    playbackState.add(playbackState.value.copyWith(
      controls: [
        if (player.hasPrevious) MediaControl.skipToPrevious,
        playing ? MediaControl.pause : MediaControl.play,
        if (player.hasNext) MediaControl.skipToNext,
      ],
      systemActions: const {MediaAction.seek, MediaAction.seekForward, MediaAction.seekBackward},
      processingState: switch (player.processingState) {
        ProcessingState.idle => AudioProcessingState.idle,
        ProcessingState.loading => AudioProcessingState.loading,
        ProcessingState.buffering => AudioProcessingState.buffering,
        ProcessingState.ready => AudioProcessingState.ready,
        ProcessingState.completed => AudioProcessingState.completed,
      },
      playing: playing,
      updatePosition: player.position,
      bufferedPosition: player.bufferedPosition,
      speed: player.speed,
      queueIndex: event.currentIndex,
    ));
  }

  @override
  Future<void> play() async {
    await player.play();
  }

  @override
  Future<void> pause() async {
    await player.pause();
  }

  @override
  Future<void> skipToNext() async {
    if (onSkipNext != null) {
      onSkipNext!();
      return;
    }
    if (player.hasNext) {
      await player.seek(Duration.zero, index: player.nextIndex);
    } else {
      final len = queue.value.isNotEmpty
          ? queue.value.length
          : player.sequence.length;
      if (len <= 0) return;
      await player.seek(Duration.zero, index: 0);
    }
  }

  @override
  Future<void> skipToPrevious() async {
    if (onSkipPrevious != null) {
      onSkipPrevious!();
      return;
    }
    if (player.hasPrevious) {
      await player.seek(Duration.zero, index: player.previousIndex);
    } else {
      final len = queue.value.isNotEmpty
          ? queue.value.length
          : player.sequence.length;
      if (len <= 0) return;
      await player.seek(Duration.zero, index: len - 1);
    }
  }

  @override
  Future<void> seek(Duration position) async {
    await player.seek(position);
  }

  @override
  Future<void> stop() async {
    await player.stop();
    await super.stop();
  }
}

class AudioPlayerService {
  final AudioPlayer player;
  final Random random = Random();
  final Map<String, Uri> _artUriCache = {};
  EighthAudioHandler? handler;
  StreamSubscription? _playerStateSub;
  StreamSubscription? _sequenceSub;
  bool _disposed = false;

  /// La playlist vive en el player (just_audio 0.10: setAudioSources /
  /// insert/remove/move directos, sin ConcatenatingAudioSource manual).
  bool get hasPlaylist => player.audioSources.isNotEmpty;
  QueueState queueState = QueueState(list: [], index: 0, label: '');
  List<String> originalOrder = [];
  bool queueShuffled = false;
  LoopMode loopMode = LoopMode.all;

  /// Se incrementa en cada mutación de la cola (set/insert/remove/move/
  /// shuffle). El controller lo usa para recalcular su hash de cola solo
  /// cuando hace falta (antes lo recomputaba cada segundo sobre TODA la
  /// lista para el snapshot de Auto).
  int queueRevision = 0;
  void _touchQueue() => queueRevision++;

  static const _unknown = AppStrings.unknown;

  AudioPlayerService({AudioPlayer? player}) : player = player ?? AudioPlayer();

  SongData? get currentSong =>
      (queueState.list.isNotEmpty && queueState.index < queueState.list.length)
          ? queueState.list[queueState.index]
          : null;

  /// Mantenido como API estable (usado en tests y ficheros de caché):
  /// delega en [fnv1a] (implementación única en utils/hashing.dart).
  static int stableHash(String s) => fnv1a(s);

  Future<void> init() async {
    try {
      await _playerStateSub?.cancel();
      _playerStateSub = player.playerStateStream.listen((state) {
        if (state.processingState == ProcessingState.idle &&
            state.playing == false &&
            queueState.list.isNotEmpty &&
            hasPlaylist) {
          debugPrint('Player is idle - audio source may have failed to load for ${currentSong?.displayTitle}');
        }
      });
      await player.setLoopMode(loopMode);
    } catch (_) {}

    // MediaSession: notificación + control externo + foreground service
    try {
      handler = await AudioService.init(
        builder: () => EighthAudioHandler(player),
        config: const AudioServiceConfig(
          androidNotificationChannelId: 'com.xly.u_8th.playback',
          androidNotificationChannelName: 'Reproducción de 8th',
          androidNotificationChannelDescription: 'Control de reproducción de música',
          androidNotificationOngoing: true,
          androidStopForegroundOnPause: true,
        ),
      );
    } catch (e) {
      debugPrint('AudioService.init error: $e');
    }
    // Notificación/sistema usan el mismo salto robusto que la UI.
    handler?.onSkipNext = playNext;
    handler?.onSkipPrevious = playPrevious;

    // Arte en la notificación al cambiar de tema: los sources que NO eran
    // el inicial se crean con withArt:false (escribir N ficheros temporales
    // por cola sería caro), así que su MediaItem llega sin artUri y la
    // notificación/pantalla de bloqueo perdía la portada al avanzar de
    // canción. Aquí la resolvemos en diferido para el tema actual.
    _sequenceSub = player.sequenceStateStream.listen((state) {
      final tag = state.currentSource?.tag;
      if (tag is! MediaItem || tag.artUri != null) return;
      final song = currentSong;
      if (song == null || song.playUri != tag.id) return;
      unawaited(() async {
        final uri = await getArtUri(song);
        if (_disposed || uri == null) return;
        final current = handler?.mediaItem.value;
        if (current?.id == tag.id && current?.artUri == null) {
          handler?.mediaItem.add(mediaItemFor(song, artUri: uri));
        }
      }());
    });
  }

  Future<Uri?> getArtUri(SongData song) async {
    // Clave estable: String.hashCode de Dart cambia en cada arranque y
    // dejaba ficheros huérfanos en cache que nunca se reutilizaban.
    final cacheKey = song.playUri;
    if (_artUriCache.containsKey(cacheKey)) {
      return _artUriCache[cacheKey];
    }
    if (song.albumArt != null && song.albumArt!.isNotEmpty) {
      try {
        // Directory.systemTemp apunta a /tmp en Android (no escribible):
        // hay que usar el directorio temporal de la app para que la
        // notificación pueda leer la portada.
        final tempDir = await getTemporaryDirectory();
        final stable = stableHash(cacheKey).toRadixString(16).padLeft(8, '0');
        final fileName = 'art_$stable.img';
        final file = File('${tempDir.path}/$fileName');
        if (!await file.exists()) {
          await file.writeAsBytes(song.albumArt!);
          // Higiene: limita el temp a ~100 portadas (las más recientes).
          unawaited(_pruneTempArt(tempDir));
        }
        final uri = file.uri;
        _artUriCache[cacheKey] = uri;
        return uri;
      } catch (_) {}
    }
    // Fallback: URI de portada de MediaStore (content://.../albumart/<id>).
    // Antes, sin bytes embebidos disponibles (p. ej. si se reproduce antes
    // de que enrichAlbumArt/_restoreCachedArt terminen, o biblioteca sin
    // arte embebido), la notificación/pantalla de bloqueo se quedaba sin
    // portada "a veces". audio_service carga la URI en el proceso de la
    // app, donde content:// es legible con READ_MEDIA_AUDIO.
    if (song.albumArtUri != null && song.albumArtUri!.isNotEmpty) {
      try {
        final uri = Uri.parse(song.albumArtUri!);
        _artUriCache[cacheKey] = uri;
        return uri;
      } catch (_) {}
    }
    return null;
  }

  /// Re-publica el MediaItem del tema actual con su portada resuelta.
  /// Llamado por el controller cuando la arte de un álbum llega en diferido
  /// (caché en disco / extracción) y el tema ya está sonando.
  Future<void> refreshCurrentMediaItem() async {
    final song = currentSong;
    if (song == null || handler == null) return;
    final current = handler!.mediaItem.value;
    if (current?.id != song.playUri) return;
    if (current?.artUri != null) return;
    final uri = await getArtUri(song);
    if (uri == null || _disposed) return;
    if (handler != null && handler!.mediaItem.value?.id == song.playUri) {
      handler!.mediaItem.add(mediaItemFor(song, artUri: uri));
    }
  }

  Future<void> _pruneTempArt(Directory tempDir) async {
    try {
      final files = tempDir
          .listSync()
          .whereType<File>()
          .where((f) {
            final name = f.path.split('/').last;
            return name.startsWith('art_') && name.endsWith('.img');
          })
          .toList();
      // Migra restos antiguos .png con hash inestable.
      final legacy = tempDir
          .listSync()
          .whereType<File>()
          .where((f) {
            final name = f.path.split('/').last;
            return name.startsWith('art_') && name.endsWith('.png');
          })
          .toList();
      for (final f in legacy) {
        try {
          await f.delete();
        } catch (_) {}
      }
      if (files.length <= 100) return;
      files.sort((a, b) {
        final sa = a.statSync().modified;
        final sb = b.statSync().modified;
        return sa.compareTo(sb);
      });
      for (var i = 0; i < files.length - 100; i++) {
        try {
          await files[i].delete();
        } catch (_) {}
      }
    } catch (_) {}
  }

  MediaItem mediaItemFor(SongData song, {Uri? artUri}) {
    return MediaItem(
      id: song.playUri,
      album: song.album ?? _unknown,
      title: song.displayTitle,
      artist: song.artist ?? _unknown,
      duration: song.durationMs == null ? null : Duration(milliseconds: song.durationMs!),
      artUri: artUri,
    );
  }

  void _syncQueueToHandler() {
    handler?.setQueueItems(queueState.list.map((s) => mediaItemFor(s)).toList());
  }

  Future<AudioSource?> buildAudioSource(SongData song, {bool withArt = true}) async {
    try {
      final artUri = withArt ? await getArtUri(song) : null;
      final mediaItem = mediaItemFor(song, artUri: artUri);
      return AudioSource.uri(song.playbackUri, tag: mediaItem);
    } catch (_) {
      return null;
    }
  }

  /// Resultado de playQueueList: cuántos fallaron (ficheros que no se
  /// pudieron abrir). La UI lo usa para avisar en vez de quedarse en silencio.
  Future<int> playQueueList(
    List<SongData> songs, {
    int initialIndex = 0,
    required String label,
    bool autoPlay = true,
    bool resumeIfSameSong = false,
    bool shuffled = false,
    SongData? currentSongBefore,
  }) async {
    if (songs.isEmpty) return 0;
    if (_disposed) return songs.length;

    final prevPath = currentSongBefore?.playUri ?? currentSong?.playUri;
    final clamped = initialIndex.clamp(0, songs.length - 1);

    final sameSong = resumeIfSameSong &&
        prevPath != null &&
        songs[clamped].playUri == prevPath;
    final initialPos = sameSong ? player.position : Duration.zero;

    // Construye (song, src) y filtra nulos SIN romper el índice: antes se
    // descartaban sources fallidos pero queueState mantenía la lista completa,
    // así que initialIndex apuntaba a otra canción.
    final keptSongs = <SongData>[];
    final audioSources = <AudioSource>[];
    var keptIndex = 0;
    for (var i = 0; i < songs.length; i++) {
      final song = songs[i];
      final withArt = i == clamped;
      final src = await buildAudioSource(song, withArt: withArt);
      if (src == null) continue;
      if (i == clamped) keptIndex = keptSongs.length;
      keptSongs.add(song);
      audioSources.add(src);
    }
    final failed = songs.length - keptSongs.length;
    if (keptSongs.isEmpty) {
      debugPrint('Playback error: ningún tema se pudo abrir ($failed fallidos)');
      return failed;
    }

    // Actualización in-place (sin reemplazar el objeto): las vistas que ya
    // tienen la referencia (full player, sheet de cola) ven el cambio con el
    // siguiente setState/notify en vez de quedarse con la lista antigua.
    // Snapshot para rollback: si setAudioSources falla, la UI no debe
    // quedar desincronizada del player (antes se actualizaba queueState
    // antes y el catch solo hacía debugPrint).
    final prevList = queueState.list;
    final prevIndex = queueState.index;
    final prevLabel = queueState.label;
    final prevOrder = List<String>.from(originalOrder);
    final prevShuffled = queueShuffled;
    queueState.list = keptSongs;
    queueState.index = keptIndex;
    queueState.label = label;
    _touchQueue();
    if (!shuffled) {
      originalOrder = keptSongs.map((s) => s.playUri).toList();
    }
    queueShuffled = shuffled;

    try {
      await player.setAudioSources(audioSources,
          initialIndex: keptIndex, initialPosition: initialPos);
      // Cola nueva = orden físico intencional: el shuffle nativo se apaga
      // (quien quita el shuffle tras una cola barajada físicamente espera
      // el orden de la lista).
      try {
        await player.setShuffleModeEnabled(false);
      } catch (_) {}
      await player.setLoopMode(loopMode);
      _syncQueueToHandler();
      if (autoPlay) {
        await player.play();
      }
    } catch (e) {
      debugPrint('Playback error: $e');
      // Rollback al estado previo para no dejar UI vs player divergentes.
      queueState.list = prevList;
      queueState.index = prevIndex;
      queueState.label = prevLabel;
      originalOrder = prevOrder;
      queueShuffled = prevShuffled;
      return songs.length;
    }
    return failed;
  }

  void playNext() => _skip(1);

  void playPrevious() => _skip(-1);

  /// Salto robusto ante toques rápidos. El destino se calcula desde
  /// queueState.index (actualizado de forma optimista aquí) y NO desde
  /// player.currentIndex/nextIndex, que no cambian hasta que el seek
  /// anterior termina de cargar: antes, dar a "siguiente" varias veces
  /// seguidas recalculaba el mismo destino y la portada/título no se
  /// actualizaban.
  void _skip(int direction) {
    final n = queueState.list.length;
    if (n == 0) return;
    var target = (queueState.index + direction) % n;
    if (target < 0) target += n;
    // Shuffle nativo activo: recorre el orden aleatorio del player.
    if (player.shuffleModeEnabled && player.shuffleIndices.length == n) {
      final order = player.shuffleIndices;
      final pos = order.indexOf(queueState.index);
      if (pos >= 0) {
        var nextPos = pos + direction;
        if (nextPos < 0) nextPos = n - 1;
        if (nextPos >= n) nextPos = 0;
        target = order[nextPos];
      }
    }
    queueState.index = target;
    if (!hasPlaylist) return;
    player.seek(Duration.zero, index: target).then(
      (_) {},
      onError: (_) {
        // Seek fallido (fichero roto…): vuelve al índice real del player
        // para que la UI no apunte a una canción que no suena.
        final real = player.currentIndex;
        if (real != null && real >= 0 && real < queueState.list.length) {
          queueState.index = real;
        }
      },
    );
  }

  Future<bool> addToQueueNext(SongData song) async {
    if (queueState.list.isEmpty || !hasPlaylist) {
      await playQueueList([song], initialIndex: 0, label: 'Cola personalizada');
      return true;
    }
    final insertPos = (queueState.index + 1).clamp(0, queueState.list.length);
    final src = await buildAudioSource(song, withArt: false);
    if (src == null || !hasPlaylist) return false;
    try {
      await player.insertAudioSource(insertPos, src);
    } catch (_) {
      return false;
    }
    queueState.list.insert(insertPos, song);
    _touchQueue();
    _syncQueueToHandler();
    return true;
  }

  Future<bool> addToQueueEnd(SongData song) async {
    if (queueState.list.isEmpty || !hasPlaylist) {
      await playQueueList([song], initialIndex: 0, label: 'Cola personalizada');
      return true;
    }
    final insertPos = queueState.list.length;
    final src = await buildAudioSource(song, withArt: false);
    if (src == null || !hasPlaylist) return false;
    try {
      await player.insertAudioSource(insertPos, src);
    } catch (_) {
      return false;
    }
    queueState.list.add(song);
    _touchQueue();
    _syncQueueToHandler();
    return true;
  }

  Future<void> removeFromQueue(int index) async {
    if (index < 0 || index >= queueState.list.length) return;
    final removingCurrent = index == queueState.index;
    if (!hasPlaylist) {
      queueState.list.removeAt(index);
      if (queueState.list.isEmpty) {
        queueState.list.clear();
        queueState.index = 0;
        queueState.label = '';
        originalOrder = [];
        queueShuffled = false;
      } else if (index < queueState.index) {
        queueState.index--;
      } else if (removingCurrent) {
        queueState.index = queueState.index.clamp(0, queueState.list.length - 1);
      }
      _touchQueue();
      // Mantiene originalOrder coherente (usa playUri estable).
      originalOrder.removeWhere((p) => queueState.list.every((s) => s.playUri != p));
      return;
    }
    if (queueState.list.length == 1) {
      await player.stop();
      try {
        await player.clearAudioSources();
      } catch (_) {}
      queueState.list.clear();
      queueState.index = 0;
      queueState.label = '';
      originalOrder = [];
      queueShuffled = false;
      _touchQueue();
      _syncQueueToHandler();
      return;
    }
    try {
      await player.removeAudioSourceAt(index);
    } catch (_) {}
    queueState.list.removeAt(index);
    _touchQueue();
    if (index < queueState.index) {
      queueState.index--;
    } else if (removingCurrent) {
      // just_audio avanza solo al siguiente; sincroniza con el índice real
      // del player en vez de hacer clamp a ciegas.
      final real = player.currentIndex;
      if (real != null && real >= 0 && real < queueState.list.length) {
        queueState.index = real;
      } else {
        queueState.index = queueState.index.clamp(0, queueState.list.length - 1);
      }
    }
    originalOrder.removeWhere((p) => queueState.list.every((s) => s.playUri != p));
    _syncQueueToHandler();
  }

  Future<void> reorderQueue(int oldIndex, int newIndex) async {
    if (oldIndex < 0 || oldIndex >= queueState.list.length) return;
    if (newIndex < 0 || newIndex > queueState.list.length) return;
    // ReorderableListView entrega newIndex como posición post-remoción (puede ser == length para append)
    if (oldIndex < newIndex) newIndex -= 1;
    if (oldIndex == newIndex) return;
    final currentUri = currentSong?.playUri;
    if (!hasPlaylist) {
      final item = queueState.list.removeAt(oldIndex);
      queueState.list.insert(newIndex, item);
      _touchQueue();
      // Re-encontrar índice del tema actual por uri (robusto, evita off-by-one)
      if (currentUri != null) {
        final newIdx = queueState.list.indexWhere((s) => s.playUri == currentUri);
        if (newIdx != -1) queueState.index = newIdx;
      }
      return;
    }
    try {
      await player.moveAudioSource(oldIndex, newIndex);
    } catch (_) {}
    final item = queueState.list.removeAt(oldIndex);
    queueState.list.insert(newIndex, item);
    _touchQueue();
    if (currentUri != null) {
      final newIdx = queueState.list.indexWhere((s) => s.playUri == currentUri);
      if (newIdx != -1) queueState.index = newIdx;
    }
  }

  /// Lógica pura del shuffle (testeable sin player): actual primero,
  /// resto aleatorio. Extraída para poder testear sin MethodChannels.
  @visibleForTesting
  static List<SongData> shuffledOrderFor(List<SongData> list, SongData current, Random rng) {
    final rest = list.where((s) => s.playUri != current.playUri).toList()..shuffle(rng);
    return [current, ...rest];
  }

  /// Lógica pura de restauración: ordena según [orderUris] y añade al final
  /// los temas nuevos (añadidos a mano tras el shuffle). Devuelve
  /// (lista, índice del actual).
  @visibleForTesting
  static (List<SongData>, int) restoredOrderFor(
    List<SongData> list,
    List<String> orderUris,
    String? currentUri,
  ) {
    final byUri = {for (final s in list) s.playUri: s};
    final restored = [
      for (final p in orderUris)
        if (byUri.containsKey(p)) byUri[p]!,
    ];
    for (final s in list) {
      if (!restored.any((r) => r.playUri == s.playUri)) restored.add(s);
    }
    var idx = restored.indexWhere((s) => s.playUri == currentUri);
    if (idx < 0) idx = 0;
    return (restored, idx);
  }

  /// Activa el shuffle NATIVO de just_audio (`setShuffleModeEnabled`):
  /// next/prev siguen el orden aleatorio sin reconstruir la playlist, así
  /// que NO hay mini-parón en la canción (el shuffle físico anterior hacía
  /// setAudioSources = recarga del tema actual = ~0.1s de silencio).
  ///
  /// El orden de `queueState.list` NO cambia: la vista de cola sigue
  /// mostrando el orden original (comportamiento estándar de players) y
  /// los "siguiente/anterior" reales se consultan vía `player.nextIndex`.
  Future<int> shuffleAllQueue() async {
    if (queueState.list.length <= 1) return 0;
    if (!hasPlaylist) {
      // Sin player cargado (tests / estado transitorio): solo flag.
      queueShuffled = true;
      return 0;
    }
    try {
      // Mantiene el tema actual como actual y baraja el resto.
      await player.setShuffleModeEnabled(true);
    } catch (_) {}
    queueShuffled = true;
    return 0;
  }

  Future<int> restoreOriginalOrder() async {
    if (!queueShuffled || queueState.list.isEmpty) return 0;
    // Shuffle nativo: apagarlo recupera el orden original al instante,
    // sin recargar nada (la lista nunca se reordenó físicamente).
    if (player.shuffleModeEnabled) {
      try {
        await player.setShuffleModeEnabled(false);
      } catch (_) {}
      queueShuffled = false;
      return 0;
    }
    // Cola físicamente barajada (p.ej. tras "Aleatorio Todo"): reconstruye
    // el orden original (mismo comportamiento que antes, incluye reload).
    if (originalOrder.isEmpty) return 0;
    final (restored, idx) = restoredOrderFor(queueState.list, originalOrder, currentSong?.playUri);
    if (restored.isEmpty) return 0;
    final failed = await playQueueList(
      restored,
      initialIndex: idx,
      label: queueState.label,
      autoPlay: player.playing,
      resumeIfSameSong: true,
      currentSongBefore: currentSong,
    );
    queueShuffled = false;
    return failed;
  }

  Future<void> toggleShuffle(bool enable) async {
    if (enable) {
      await shuffleAllQueue();
    } else {
      await restoreOriginalOrder();
    }
  }

  Future<LoopMode> cycleLoopMode() async {
    loopMode = switch (loopMode) {
      LoopMode.off => LoopMode.all,
      LoopMode.all => LoopMode.one,
      LoopMode.one => LoopMode.off,
    };
    try {
      await player.setLoopMode(loopMode);
    } catch (_) {}
    return loopMode;
  }

  Future<void> ensureLoopAll() async {
    if (loopMode != LoopMode.all) {
      loopMode = LoopMode.all;
      try {
        await player.setLoopMode(loopMode);
      } catch (_) {}
    }
  }

  Future<void> dispose() async {
    if (_disposed) return;
    _disposed = true;
    try {
      await _playerStateSub?.cancel();
    } catch (_) {}
    try {
      await _sequenceSub?.cancel();
    } catch (_) {}
    try {
      await handler?.disposeHandler();
    } catch (_) {}
    try {
      await player.dispose();
    } catch (_) {}
  }
}
