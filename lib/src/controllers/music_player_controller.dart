import 'dart:async';
import 'dart:math' show Random;

import 'package:flutter/foundation.dart';
import 'package:just_audio/just_audio.dart';

import '../../auto/auto_bridge.dart';
import '../models/song_data.dart';
import '../services/audio_player_service.dart';
import '../services/library_service.dart';
import '../services/lyrics_service.dart';
import '../theme/palette_service.dart';
import '../utils/artists.dart';

class LyricsSyncResult {
  final int total;
  final int missingBefore;
  final int fetched;
  final bool alreadySyncing;
  final bool emptyLibrary;
  const LyricsSyncResult({
    this.total = 0,
    this.missingBefore = 0,
    this.fetched = 0,
    this.alreadySyncing = false,
    this.emptyLibrary = false,
  });
}

class MusicPlayerController extends ChangeNotifier {
  final LibraryService libraryService;
  final AudioPlayerService audioService;
  final LyricsService lyricsService;

  MusicPlayerController({
    LibraryService? libraryService,
    AudioPlayerService? audioService,
    LyricsService? lyricsService,
  })  : libraryService = libraryService ?? LibraryService(),
        audioService = audioService ?? AudioPlayerService(),
        lyricsService = lyricsService ?? LyricsService.shared;

  // UI State
  ViewTab currentTab = ViewTab.albums;
  bool reorderingAlbums = false;
  bool searching = false;
  String searchQuery = '';

  // Library State
  List<SongData> songs = [];
  Map<String, List<SongData>> grouped = {};
  bool loading = false;
  bool initialized = false;
  List<String> albumOrder = [];
  final Set<String> selectedAlbums = {};
  bool albumOrderChanged = false;

  // Lyrics sync state
  bool lyricsSyncing = false;
  int lyricsSyncedCount = 0;
  int lyricsTotalToSync = 0;

  // Delegated audio state
  QueueState get queueState => audioService.queueState;
  set queueState(QueueState v) => audioService.queueState = v;
  bool get queueShuffled => audioService.queueShuffled;
  LoopMode get loopMode => audioService.loopMode;
  AudioPlayer get player => audioService.player;
  bool get hasPlaylist => audioService.hasPlaylist;
  SongData? get currentSong => audioService.currentSong;
  Random get random => audioService.random;

  StreamSubscription<Map<String, dynamic>>? _autoSub;
  StreamSubscription<int?>? _indexSub;
  StreamSubscription<ProcessingState>? _processingSub;
  Timer? _autoSnapshotTimer;

  Future<void> init() async {
    unawaited(libraryService.ensureNotificationPermission());
    // Lecturas de prefs en paralelo con audioService.init: antes el
    // spinner inicial esperaba init (AudioService es costoso) y DESPUÉS
    // dos lecturas de prefs en serie.
    final albumOrderF = libraryService.getAlbumOrder();
    final treeUriF = libraryService.getTreeUri();
    final filePathsF = libraryService.getFilePaths();
    await audioService.init();
    albumOrder = await albumOrderF;

    // Listen to player index to keep queue index in sync + palette pre-cálculo (después del slide)
    _indexSub = audioService.player.currentIndexStream.listen((index) {
      if (index != null && index >= 0 && index < audioService.queueState.list.length) {
        if (audioService.queueState.index != index) {
          audioService.queueState.index = index;
          notifyListeners();
        }
      }
      // Paleta después del slide (no durante la animación de bajada).
      // Coalescing: SOLO si cambió la canción (antes cada evento de índice
      // programaba otro delayed de 320ms aunque fuese la misma portada).
      final song = audioService.currentSong;
      final uri = song?.playUri;
      if (uri == null || uri == _lastPaletteSong) return;
      _lastPaletteSong = uri;
      final art = song?.albumArt;
      Future.delayed(const Duration(milliseconds: 320), () {
        if (!hasListeners) return;
        unawaited(PaletteService.updateForArt(art));
      });
    });

    _processingSub = audioService.player.processingStateStream.listen((state) async {
      // Con LoopMode.all/one just_audio avanza/repite solo; este fallback es
      // solo para LoopMode.off (avance manual al siguiente y play explícito,
      // antes faltaba el play y el siguiente quedaba en pausa).
      if (state == ProcessingState.completed &&
          audioService.loopMode == LoopMode.off &&
          audioService.queueState.list.isNotEmpty) {
        if (audioService.queueState.index < audioService.queueState.list.length - 1) {
          try {
            await audioService.player.seek(Duration.zero, index: audioService.player.nextIndex);
            await audioService.player.play();
          } catch (_) {}
        } else {
          // Fin de cola en modo off: detiene y deja el estado consistente
          // para la notificación/Auto en vez de quedarse en "completed".
          try {
            await audioService.player.seek(Duration.zero);
            await audioService.player.stop();
          } catch (_) {}
          _pushAutoSnapshot(force: true);
        }
      }
    });

    final treeUri = await treeUriF;
    if (treeUri != null && treeUri.isNotEmpty) {
      await loadFromTreeUri(treeUri);
      _startAutoBridge();
      return;
    }
    final savedPaths = await filePathsF;
    if (savedPaths != null && savedPaths.isNotEmpty) {
      // Sin existsSync en UI: loadSongsIsolate filtra inexistentes en background.
      await loadSongs(savedPaths);
      // loadSongs marca initialized; si quedó vacía (todo borrado),
      // se muestra empty_state en vez de spinner.
      _startAutoBridge();
      return;
    }
    initialized = true;
    notifyListeners();
    _startAutoBridge();
  }

  void _startAutoBridge() {
    // Evita doble suscripción en hot-restart/doble init (fuga + doble play).
    _autoSub?.cancel();
    _autoSnapshotTimer?.cancel();
    _autoSub = AutoBridge.events.listen((event) {
      final action = event['action'] as String?;
      if (action == 'playQueue') {
        final idx = (event['index'] as int?) ?? 0;
        final tracksRaw = event['tracks'] as List<dynamic>? ?? [];
        if (tracksRaw.isEmpty) return;
        final newSongs = <SongData>[];
        for (final raw in tracksRaw) {
          final m = Map<String, dynamic>.from(raw as Map);
          final uri = m['uri'] as String? ?? '';
          if (uri.isEmpty) continue;
          newSongs.add(SongData(
            path: uri,
            contentUri: uri,
            title: m['title'] as String?,
            artist: m['artist'] as String?,
            album: m['album'] as String?,
            durationMs: (m['durationMs'] as num?)?.toInt(),
          ));
        }
        if (newSongs.isNotEmpty) {
          playQueueList(
            newSongs,
            initialIndex: idx.clamp(0, newSongs.length - 1),
            label: newSongs[idx.clamp(0, newSongs.length - 1)].album ?? 'Auto',
            autoPlay: true,
          );
        }
      } else if (action == 'control') {
        final cmd = event['command'] as String?;
        switch (cmd) {
          case 'play':
            unawaited(audioService.player.play());
            break;
          case 'pause':
            unawaited(audioService.player.pause());
            break;
          case 'next':
            playNext();
            break;
          case 'prev':
            playPrevious();
            break;
          case 'cycleLoop':
            unawaited(cycleLoopMode().then((_) {
              if (hasListeners) notifyListeners();
              _pushAutoSnapshot(force: true);
            }));
            break;
          case 'toggleShuffle':
            unawaited(toggleQueueShuffle(!audioService.queueShuffled).then((_) {
              if (hasListeners) notifyListeners();
              _pushAutoSnapshot(force: true);
            }));
            break;
          case 'shuffleAll':
            // "Aleatorio total" de Auto: baraja la biblioteca local sin
            // traer 5000 temas por el EventChannel (TransactionTooLarge).
            unawaited(playShuffled().then((_) {
              if (hasListeners) notifyListeners();
              _pushAutoSnapshot(force: true);
            }));
            break;
        }
      } else if (action == 'queue') {
        final cmd = event['command'] as String?;
        if (cmd == 'playIndex') {
          final idx = (event['index'] as int?) ?? 0;
          if (idx >= 0 && idx < audioService.queueState.list.length) {
            unawaited(audioService.player.seek(Duration.zero, index: idx));
          }
        }
      }
    }, onError: (_) {});

    // Snapshot con change-detection: antes se serializaba la cola entera
    // cada 500ms incluso en pausa (batería + spam MethodChannel).
    // Ahora tick de 1s + firma: solo se pushea si cambia el estado o
    // avanza la posición ≥2s (o heartbeat cada 10s).
    _autoSnapshotTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      _pushAutoSnapshot();
    });
  }

  String _lastAutoKey = '';
  int _lastAutoPosSec = -1;
  DateTime _lastAutoPush = DateTime.fromMillisecondsSinceEpoch(0);
  // Hash de cola cacheado por revisión: antes se recorría TODA la lista
  // cada segundo para recalcularlo aunque la cola no hubiera cambiado.
  int _lastAutoQueueRev = -1;
  int _cachedQueueHash = 0;
  // Dedup de paleta: última canción para la que se programó el cálculo.
  String? _lastPaletteSong;

  void _pushAutoSnapshot({bool force = false}) {
    try {
      final song = currentSong;
      // Sin cola no hay nada que sincronizar: evita builds + IPC cada
      // segundo en frío.
      if (song == null) return;
      final pos = audioService.player.position;
      final dur = audioService.player.duration ?? Duration.zero;
      final playing = audioService.player.playing;
      final list = audioService.queueState.list;
      final lm = switch (audioService.loopMode) {
        LoopMode.off => 0,
        LoopMode.all => 1,
        LoopMode.one => 2,
      };
      // Firma barata sin serializar títulos: uri actual + flags + longitud
      // + hash de uris (FNV-1a sobre longitudes para no recorrer strings).
      // Recalculado solo cuando la cola muda (queueRevision).
      if (_lastAutoQueueRev != audioService.queueRevision) {
        var queueHash = 0x811c9dc5;
        for (final s in list) {
          final u = s.playUri;
          queueHash ^= u.length;
          queueHash = (queueHash * 0x01000193) & 0xFFFFFFFF;
        }
        _cachedQueueHash = queueHash;
        _lastAutoQueueRev = audioService.queueRevision;
      }
      final key =
          '${song.playUri}|$playing|${audioService.queueShuffled}|$lm|${audioService.queueState.index}|${list.length}|$_cachedQueueHash';
      final posSec = pos.inSeconds;
      final now = DateTime.now();
      final posChanged = (posSec - _lastAutoPosSec).abs() >= 2;
      final keyChanged = key != _lastAutoKey;
      // Heartbeat SOLO suena: en pausa el coche ya tiene el estado y el
      // push cada 10s despertaba CPU/red indefinidamente.
      final heartbeat = playing && now.difference(_lastAutoPush).inSeconds >= 10;
      // En pausa y sin cambios: no pushear (el coche ya tiene el estado).
      if (!force && !keyChanged && !(playing && posChanged) && !heartbeat) {
        return;
      }
      _lastAutoKey = key;
      _lastAutoPosSec = posSec;
      _lastAutoPush = now;
      // La cola completa solo viaja si cambió (keyChanged/force): los ticks
      // de posición son ligeros y Kotlin conserva la cola anterior.
      // Evita TransactionTooLarge con 500 temas cada 2s.
      final includeQueue = force || keyChanged;
      final payload = <String, dynamic>{
        'uri': song.playUri,
        'title': song.displayTitle,
        'artist': song.artist ?? '',
        'album': song.album ?? '',
        'positionMs': pos.inMilliseconds,
        'durationMs': dur.inMilliseconds,
        'playing': playing,
        'shuffled': audioService.queueShuffled,
        'loopMode': lm,
        'queueIndex': audioService.queueState.index,
        'queueHash': _cachedQueueHash,
      };
      if (includeQueue) {
        payload['queue'] = list
            .map((s) => {
                  'uri': s.playUri,
                  'title': s.displayTitle,
                  'artist': s.artist ?? '',
                  'album': s.album ?? '',
                  'durationMs': s.durationMs ?? 0,
                })
            .toList();
      }
      AutoBridge.pushPlaybackSnapshot(payload);
    } catch (_) {}
  }

  // Library methods
  Future<void> pickFolder() async {
    final result = await libraryService.pickFolder();
    if (result == null) return;
    await loadFromTreeUri(result);
  }

  Future<void> pickFiles() async {
    final paths = await libraryService.pickFiles();
    if (paths == null) return;
    await loadSongs(paths);
  }

  int _enrichGen = 0;

  Future<void> loadFromTreeUri(String treeUri) async {
    loading = true;
    notifyListeners();
    await libraryService.ensureAudioPermission();
    final fileList = await libraryService.listAudioFiles(treeUri);
    if (fileList == null || fileList.isEmpty) {
      // treeUri de FilePicker es un path de filesystem: si MediaStore
      // falla (scoped storage), escaneo directo.
      if (treeUri.startsWith('/')) {
        try {
          final scanned =
              await libraryService.scanDirectoryForAudio(treeUri);
          if (scanned.isNotEmpty) {
            // Persiste el scan para no reintentar la vía fallida en cada arranque.
            await libraryService.saveFilePaths(scanned);
            await loadSongs(scanned, persistPaths: false);
            return;
          }
        } catch (_) {}
      }
      await fallbackFilePick();
      return;
    }
    await loadFromRichList(fileList);
  }

  Future<void> loadFromRichList(List<Map<dynamic, dynamic>> fileList) async {
    loading = true;
    notifyListeners();
    final gen = ++_enrichGen;
    final newSongs = libraryService.fromRichList(fileList);
    songs = newSongs;
    grouped = libraryService.groupByAlbum(songs);
    _rebuildSearchIndex();
    initialized = true;
    loading = false;
    notifyListeners();
    unawaited(_restoreCachedArt(gen));
    unawaited(enrichAlbumArt(gen));
  }

  /// Artista del álbum: el principal del primer tema (ver [Artists]).
  String? _albumArtist(List<SongData> albumSongs) {
    return Artists.albumArtistFor(albumSongs);
  }

  // Portadas desde caché en disco: aparecen al instante en el arranque
  Future<void> _restoreCachedArt([int? gen]) async {
    final myGen = gen ?? _enrichGen;
    var changed = false;
    // Snapshot para no iterar un mapa que puede reasignarse a mitad.
    final entries = Map<String, List<SongData>>.of(grouped).entries.toList();
    for (final entry in entries) {
      if (myGen != _enrichGen || !initialized) return;
      final albumSongs = entry.value;
      if (albumSongs.any((s) => s.albumArt != null)) continue;
      final cached = await libraryService.loadCachedAlbumArt(
        entry.key,
        artist: _albumArtist(albumSongs),
      );
      if (myGen != _enrichGen) return;
      if (cached == null) continue;
      for (final s in albumSongs) {
        s.albumArt = cached;
      }
      changed = true;
      // Si el tema actual es de este álbum y ya está sonando, re-publica
      // el MediaItem para que la notificación/pantalla de bloqueo tenga
      // portada (antes llegaba "a veces": dependía de si la arte había
      // cargado antes de darle al play).
      final curUri = audioService.currentSong?.playUri;
      if (curUri != null && albumSongs.any((s) => s.playUri == curUri)) {
        unawaited(audioService.refreshCurrentMediaItem());
      }
    }
    if (changed && myGen == _enrichGen) notifyListeners();
  }

  Future<void> enrichAlbumArt([int? gen]) async {
    final myGen = gen ?? ++_enrichGen;
    // Snapshot para no iterar un mapa que puede reasignarse a mitad.
    final entries = Map<String, List<SongData>>.of(grouped).entries.toList();
    for (final entry in entries) {
      if (myGen != _enrichGen || !initialized) return;
      final albumSongs = entry.value;
      if (albumSongs.any((s) => s.albumArt != null && s.albumArt!.isNotEmpty)) continue;
      Uint8List? bytes;
      for (final s in albumSongs) {
        if (myGen != _enrichGen) return;
        bytes = await libraryService.extractArt(s);
        if (bytes != null) break;
      }
      if (myGen != _enrichGen) return;
      if (bytes == null) continue;
      for (final s in albumSongs) {
        s.albumArt = bytes;
      }
      final curUri = audioService.currentSong?.playUri;
      if (curUri != null && albumSongs.any((s) => s.playUri == curUri)) {
        unawaited(audioService.refreshCurrentMediaItem());
      }
      unawaited(libraryService.cacheAlbumArt(
        entry.key,
        bytes,
        artist: _albumArtist(albumSongs),
      ));
      if (myGen == _enrichGen) notifyListeners();
    }
  }

  /// Artistas únicos normalizados: "Queen, Bowie" cuenta 2 y
  /// "X feat. Y" no duplica al artista principal (antes cada variante
  /// de feat. contaba como artista distinto en el diálogo de info).
  /// "Tyler, The Creator" cuenta 1 (la coma es parte del nombre).
  int countUniqueArtists() {
    final set = <String>{};
    for (final s in songs) {
      for (final a in Artists.parseArtists(s.artist)) {
        set.add(Artists.normalize(a));
      }
    }
    return set.length;
  }

  /// Limpieza manual de caché de portadas (regenerable). Devuelve nº borradas.
  Future<int> clearArtCache() async {
    return libraryService.clearArtCache();
  }

  bool _lyricsCancelRequested = false;

  void cancelLyricsSync() {
    _lyricsCancelRequested = true;
  }

  /// Sincronización manual desde el menú (tres puntos).
  /// Verifica faltantes, descarga y retorna resultado para SnackBars.
  /// Cancelable vía [cancelLyricsSync] (antes no se podía interrumpir un
  /// sync de cientos de temas que podía durar horas sin red).
  Future<LyricsSyncResult> syncLyricsManual({void Function(int missing)? onMissingCount}) async {
    if (songs.isEmpty) {
      return const LyricsSyncResult(emptyLibrary: true);
    }
    if (lyricsSyncing) {
      return const LyricsSyncResult(alreadySyncing: true);
    }
    lyricsSyncing = true;
    _lyricsCancelRequested = false;
    lyricsTotalToSync = songs.length;
    lyricsSyncedCount = 0;
    notifyListeners();

    try {
      // Contar faltantes con UN listado del directorio (antes cada hasLrc
      // hacía 2 stats por canción y aquí se recorría toda la biblioteca).
      final lrcNames = await lyricsService.cachedLrcNames();
      int missing = 0;
      for (final s in songs) {
        if (_lyricsCancelRequested) break;
        if (!lyricsService.hasLrcIn(lrcNames, s)) missing++;
      }
      if (_lyricsCancelRequested) {
        lyricsSyncing = false;
        notifyListeners();
        return LyricsSyncResult(total: songs.length, missingBefore: missing, fetched: 0);
      }
      if (missing == 0) {
        lyricsSyncing = false;
        notifyListeners();
        return LyricsSyncResult(total: songs.length, missingBefore: 0, fetched: 0);
      }
      onMissingCount?.call(missing);
      final fetched = await lyricsService.syncMissing(
        songs,
        onProgress: (done, total) {
          lyricsSyncedCount = done;
          lyricsTotalToSync = total;
          if (done % 5 == 0 || done == total) notifyListeners();
        },
        shouldCancel: () => _lyricsCancelRequested,
      );
      lyricsSyncing = false;
      notifyListeners();
      return LyricsSyncResult(total: songs.length, missingBefore: missing, fetched: fetched);
    } catch (_) {
      lyricsSyncing = false;
      notifyListeners();
      return LyricsSyncResult(total: songs.length, missingBefore: 0, fetched: 0);
    }
  }

  Future<void> loadSongs(List<String> paths, {bool persistPaths = true}) async {
    loading = true;
    notifyListeners();
    final gen = ++_enrichGen;
    if (persistPaths) {
      try {
        await libraryService.saveFilePaths(paths);
      } catch (_) {}
    }
    final newSongs = await libraryService.loadSongsIsolate(paths);
    songs = newSongs;
    grouped = libraryService.groupByAlbum(songs);
    _rebuildSearchIndex();
    initialized = true;
    loading = false;
    notifyListeners();
    unawaited(_restoreCachedArt(gen));
  }

  Future<void> fallbackFilePick() async {
    final result = await libraryService.pickFiles();
    // pickFiles already handles dialog; but we need to handle the case where pickFiles returns null due to cancel
    // In original fallback, it used FilePicker directly; libraryService.pickFiles does same
    if (result == null) {
      loading = false;
      initialized = true;
      notifyListeners();
      return;
    }
    // libraryService.pickFiles already cleared treeUri and saved filePaths;
    // solo falta cargar la biblioteca con lo devuelto.
    await loadSongs(result);
  }

  void toggleAlbum(String album) {
    if (selectedAlbums.contains(album)) {
      selectedAlbums.remove(album);
    } else {
      selectedAlbums.add(album);
    }
    notifyListeners();
  }

  List<String> sortedAlbumKeys() {
    return libraryService.sortedAlbumKeys(grouped, albumOrder);
  }

  Future<void> reorderAlbums(List<String> newOrder) async {
    albumOrder = newOrder;
    albumOrderChanged = true;
    await libraryService.saveAlbumOrder(albumOrder);
    notifyListeners();
  }

  // Audio methods - delegate to audioService but also notify
  // Optimizado: notifica inmediatamente para mini-player, heavy work después.
  // Devuelve nº de temas que no se pudieron abrir (antes se silenciaban y
  // el índice se desplazaba).
  int lastFailedCount = 0;

  Future<int> playQueueList(
    List<SongData> list, {
    int initialIndex = 0,
    required String label,
    bool autoPlay = true,
    bool resumeIfSameSong = false,
    bool shuffled = false,
  }) async {
    if (list.isEmpty) return 0;
    final before = currentSong;
    final clamped = initialIndex.clamp(0, list.length - 1);
    // 1) Update UI inmediatamente (mini-player aparece sin esperar setAudioSource).
    // In-place para no romper las referencias que ya tienen las vistas abiertas.
    audioService.queueState.list = List.from(list);
    audioService.queueState.index = clamped;
    audioService.queueState.label = label;
    audioService.queueRevision++;
    if (!shuffled) {
      audioService.originalOrder = list.map((s) => s.playUri).toList();
    }
    audioService.queueShuffled = shuffled;
    lastFailedCount = 0;
    notifyListeners();
    // 2) Paleta DESPUÉS del slide del mini-player (340ms) para no hacer jank durante la bajada de la lista.
    final artForPalette = audioService.queueState.list[clamped].albumArt;
    final paletteSongUri = audioService.queueState.list[clamped].playUri;
    final paletteAlreadyScheduled = paletteSongUri == _lastPaletteSong;
    if (!paletteAlreadyScheduled) _lastPaletteSong = paletteSongUri;
    Future.delayed(const Duration(milliseconds: 340), () {
      if (!hasListeners || paletteAlreadyScheduled) return;
      // Solo si seguimos en el mismo tema (evita palette de canción vieja)
      if (audioService.queueState.list.isNotEmpty && audioService.queueState.index == clamped) {
        unawaited(PaletteService.updateForArt(artForPalette));
      }
    });
    // 3) Heavy work (buildAudioSource + setAudioSource) con remapeo de índice
    final failed = await audioService.playQueueList(
      list,
      initialIndex: clamped,
      label: label,
      autoPlay: autoPlay,
      resumeIfSameSong: resumeIfSameSong,
      shuffled: shuffled,
      currentSongBefore: before,
    );
    lastFailedCount = failed;
    _pushAutoSnapshot(force: true);
    if (hasListeners) notifyListeners();
    return failed;
  }

  Future<void> playShuffled() async {
    if (songs.isEmpty) return;
    audioService.originalOrder = songs.map((s) => s.playUri).toList();
    if (currentSong != null) {
      final current = currentSong!;
      final rest = songs.where((s) => s.playUri != current.playUri).toList()..shuffle(random);
      await playQueueList([current, ...rest], initialIndex: 0, label: 'Aleatorio Todo', autoPlay: audioService.player.playing, resumeIfSameSong: true, shuffled: true);
    } else {
      final shuffled = List<SongData>.from(songs)..shuffle(random);
      await playQueueList(shuffled, initialIndex: 0, label: 'Aleatorio Todo', autoPlay: true, shuffled: true);
    }
  }

  Future<void> playAlbum(String albumName, List<SongData> albumSongs, {int startIndex = 0}) {
    return playQueueList(albumSongs, initialIndex: startIndex, label: albumName);
  }

  Future<void> playAlbumShuffled(String albumName, List<SongData> albumSongs) async {
    if (albumSongs.isEmpty) return;
    audioService.originalOrder = albumSongs.map((s) => s.playUri).toList();
    if (currentSong != null && albumSongs.any((s) => s.playUri == currentSong!.playUri)) {
      final current = currentSong!;
      final rest = albumSongs.where((s) => s.playUri != current.playUri).toList()..shuffle(random);
      await playQueueList([current, ...rest], initialIndex: 0, label: '$albumName (Aleatorio)', autoPlay: audioService.player.playing, resumeIfSameSong: true, shuffled: true);
    } else {
      final shuffled = List<SongData>.from(albumSongs)..shuffle(random);
      await playQueueList(shuffled, initialIndex: 0, label: '$albumName (Aleatorio)', autoPlay: true, shuffled: true);
    }
  }

  Future<void> playSelectedShuffle() async {
    if (selectedAlbums.isEmpty) return;
    final list = selectedAlbums.expand((a) => grouped[a] ?? <SongData>[]).toList();
    if (list.isEmpty) return;
    audioService.originalOrder = list.map((s) => s.playUri).toList();
    selectedAlbums.clear();
    if (currentSong != null && list.any((s) => s.playUri == currentSong!.playUri)) {
      final current = currentSong!;
      final rest = list.where((s) => s.playUri != current.playUri).toList()..shuffle(random);
      await playQueueList([current, ...rest], initialIndex: 0, label: 'Aleatorio de Selección', autoPlay: audioService.player.playing, resumeIfSameSong: true, shuffled: true);
    } else {
      final shuffled = List<SongData>.from(list)..shuffle(random);
      await playQueueList(shuffled, initialIndex: 0, label: 'Aleatorio de Selección', autoPlay: true, shuffled: true);
    }
    currentTab = ViewTab.albums;
    notifyListeners();
  }

  void playNext() {
    audioService.playNext();
    notifyListeners();
  }

  void playPrevious() {
    audioService.playPrevious();
    notifyListeners();
  }

  Future<String?> addToQueueNext(SongData song) async {
    final success = await audioService.addToQueueNext(song);
    if (success) notifyListeners();
    return success ? 'Añadida a continuación: ${song.displayTitle}' : null;
  }

  Future<String?> addToQueueEnd(SongData song) async {
    final success = await audioService.addToQueueEnd(song);
    if (success) notifyListeners();
    return success ? 'Añadida al final de la cola: ${song.displayTitle}' : null;
  }

  Future<void> removeFromQueue(int index) async {
    await audioService.removeFromQueue(index);
    notifyListeners();
  }

  Future<void> reorderQueue(int oldIndex, int newIndex) async {
    await audioService.reorderQueue(oldIndex, newIndex);
    notifyListeners();
  }

  Future<void> toggleQueueShuffle(bool enable) async {
    await audioService.toggleShuffle(enable);
    _pushAutoSnapshot(force: true);
    notifyListeners();
  }

  Future<LoopMode> cycleLoopMode() async {
    final m = await audioService.cycleLoopMode();
    _pushAutoSnapshot(force: true);
    notifyListeners();
    return m;
  }

  void setTab(ViewTab tab) {
    currentTab = tab;
    notifyListeners();
  }

  void setReordering(bool v) {
    reorderingAlbums = v;
    notifyListeners();
  }

  void startSearch() {
    searching = true;
    notifyListeners();
  }

  void closeSearch() {
    searching = false;
    searchQuery = '';
    notifyListeners();
  }

  void updateSearchQuery(String q) {
    searchQuery = q;
    notifyListeners();
  }

  void clearSelection() {
    selectedAlbums.clear();
    notifyListeners();
  }

  // ---- Búsqueda con índice precalculado ----
  // Antes cada rebuild del ResultsView normalizaba de nuevo TODOS los
  // títulos/artistas/álbumes (3 _normSearch x canción); ahora los campos
  // normalizados se calculan una vez por carga de biblioteca.

  /// Índice de búsqueda: campos ya normalizados por canción.
  List<({SongData song, String albumKey, String title, String artist, String album})>
      _searchIndex = const [];
  Map<String, List<SongData>>? _indexSource;

  void _rebuildSearchIndex() {
    // Mismo orden que la UI de álbumes (sortedAlbumKeys) para que los
    // resultados conserven el orden de antes.
    final idx = <({SongData song, String albumKey, String title, String artist, String album})>[];
    for (final albumKey in sortedAlbumKeys()) {
      final albumNorm = _normSearch(albumKey);
      for (final s in grouped[albumKey] ?? const <SongData>[]) {
        idx.add((
          song: s,
          albumKey: albumKey,
          title: _normSearch(s.displayTitle),
          artist: _normSearch(s.artist ?? ''),
          album: albumNorm,
        ));
      }
    }
    _searchIndex = idx;
    _indexSource = grouped;
  }

  static const Map<String, String> _diacritics = {
    'á': 'a', 'à': 'a', 'ä': 'a', 'â': 'a', 'ã': 'a',
    'é': 'e', 'è': 'e', 'ë': 'e', 'ê': 'e',
    'í': 'i', 'ì': 'i', 'ï': 'i', 'î': 'i',
    'ó': 'o', 'ò': 'o', 'ö': 'o', 'ô': 'o', 'õ': 'o',
    'ú': 'u', 'ù': 'u', 'ü': 'u', 'û': 'u',
    'ñ': 'n', 'ç': 'c', 'ý': 'y', 'ÿ': 'y',
  };

  static String _normSearch(String s) {
    // Normaliza tildes para que "canción" encuentre "cancion", en UN solo
    // pase (antes eran 25 replaceAll sobre el string completo).
    final buffer = StringBuffer();
    for (final rune in s.trim().toLowerCase().runes) {
      final ch = String.fromCharCode(rune);
      buffer.write(_diacritics[ch] ?? ch);
    }
    return buffer.toString();
  }

  List<MapEntry<String, SongData>> searchResults(String query) {
    // Auto-sanado: si grouped se reasignó sin pasar por loadSongs/
    // loadFromRichList (tests, mutación directa), el índice se regenera.
    if (!identical(_indexSource, grouped)) _rebuildSearchIndex();
    final q = _normSearch(query);
    if (q.isEmpty) return const [];
    return [
      for (final e in _searchIndex)
        if (e.title.contains(q) || e.artist.contains(q) || e.album.contains(q))
          MapEntry(e.albumKey, e.song),
    ];
  }

  @override
  void dispose() {
    _autoSub?.cancel();
    _indexSub?.cancel();
    _processingSub?.cancel();
    _autoSnapshotTimer?.cancel();
    audioService.dispose();
    super.dispose();
  }
}
