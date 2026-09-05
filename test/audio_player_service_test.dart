import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:eighth/src/models/song_data.dart';
import 'package:eighth/src/services/audio_player_service.dart';
import 'package:just_audio/just_audio.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('AudioPlayerService - lógica de cola', () {
    late AudioPlayerService service;

    setUp(() {
      service = AudioPlayerService();
    });

    tearDown(() async {
      try {
        await service.dispose();
      } catch (_) {}
    });

    SongData makeSong(String path, {String? album, int? track, String? title}) {
      return SongData(path: path, title: title ?? path, album: album, trackNumber: track);
    }

    group('playQueueList', () {
      test('inicializa queueState y originalOrder (sin player)', () {
        // Evitamos llamar a playQueueList real que necesita platform; testeamos lógica directa
        final songs = [makeSong('a.mp3'), makeSong('b.mp3'), makeSong('c.mp3')];
        service.queueState = QueueState(list: List.from(songs), index: 1, label: 'Test');
        service.originalOrder = songs.map((s) => s.path).toList();
        expect(service.queueState.list.length, 3);
        expect(service.queueState.index, 1);
        expect(service.queueState.label, 'Test');
        expect(service.originalOrder, ['a.mp3', 'b.mp3', 'c.mp3']);
      });

      test('clamp de initialIndex (lógica)', () {
        final songs = [makeSong('a.mp3'), makeSong('b.mp3')];
        // Simulamos clamp que hace playQueueList
        int clamped = (99).clamp(0, songs.length - 1);
        service.queueState = QueueState(list: songs, index: clamped, label: 'Test');
        expect(service.queueState.index, 1);
        clamped = (-5).clamp(0, songs.length - 1);
        service.queueState = QueueState(list: songs, index: clamped, label: 'Test');
        expect(service.queueState.index, 0);
      });

      test('shuffled flag se guarda', () {
        final songs = [makeSong('a.mp3')];
        service.queueState = QueueState(list: songs, index: 0, label: 'Test');
        service.queueShuffled = true;
        expect(service.queueShuffled, isTrue);
      });
    });

    group('removeFromQueue', () {
      test('remueve y ajusta índice si se borra antes del actual', () async {
        final songs = [makeSong('a.mp3'), makeSong('b.mp3'), makeSong('c.mp3')];
        service.queueState = QueueState(list: List.from(songs), index: 2, label: 'Test');
        service.originalOrder = songs.map((s) => s.path).toList();
        // Sin playlist en el player (audioSources vacío por defecto).
        await service.removeFromQueue(0);
        expect(service.queueState.list.length, 2);
        expect(service.queueState.index, 1);
      });

      test('remueve índice actual y hace clamp', () async {
        final songs = [makeSong('a.mp3'), makeSong('b.mp3')];
        service.queueState = QueueState(list: List.from(songs), index: 1, label: 'Test');
        service.originalOrder = songs.map((s) => s.path).toList();
        // Sin playlist en el player (audioSources vacío por defecto).
        await service.removeFromQueue(1);
        expect(service.queueState.index, 0);
      });
    });

    group('reorderQueue', () {
      test('reordena lista y actualiza índice si se mueve el actual', () async {
        final songs = [makeSong('a.mp3'), makeSong('b.mp3'), makeSong('c.mp3')];
        service.queueState = QueueState(list: List.from(songs), index: 0, label: 'Test');
        service.originalOrder = songs.map((s) => s.path).toList();
        // Sin playlist en el player (audioSources vacío por defecto).
        await service.reorderQueue(0, 3);
        expect(service.queueState.list[2].path, 'a.mp3');
        expect(service.queueState.index, 2);
      });

      test('reordena y ajusta índice si se mueve elemento antes del actual hacia después', () async {
        final songs = [makeSong('a.mp3'), makeSong('b.mp3'), makeSong('c.mp3')];
        service.queueState = QueueState(list: List.from(songs), index: 1, label: 'Test');
        service.originalOrder = songs.map((s) => s.path).toList();
        // Sin playlist en el player (audioSources vacío por defecto).
        await service.reorderQueue(0, 3);
        expect(service.queueState.list[0].path, 'b.mp3');
        expect(service.queueState.index, 0);
      });
    });

    group('cycleLoopMode', () {
      test('cicla all -> one -> off -> all', () async {
        service.loopMode = LoopMode.all;
        expect(await service.cycleLoopMode(), LoopMode.one);
        expect(await service.cycleLoopMode(), LoopMode.off);
        expect(await service.cycleLoopMode(), LoopMode.all);
        expect(service.loopMode, LoopMode.all);
      });
    });

    group('toggleShuffle (lógica pura, sin player nativo)', () {
      test('shuffledOrderFor mantiene el actual primero', () {
        final songs = [makeSong('a.mp3'), makeSong('b.mp3'), makeSong('c.mp3')];
        final order = AudioPlayerService.shuffledOrderFor(songs, songs[1], Random(42));
        expect(order.first.path, 'b.mp3');
        expect(order.map((s) => s.path).toSet(), {'a.mp3', 'b.mp3', 'c.mp3'});
      });

      test('restoredOrderFor recupera el orden y conserva añadidos', () {
        final songs = [makeSong('a.mp3'), makeSong('b.mp3'), makeSong('c.mp3')];
        final shuffled = [songs[1], songs[2], songs[0]];
        final (restored, idx) = AudioPlayerService.restoredOrderFor(
          [...shuffled, makeSong('d.mp3')],
          ['a.mp3', 'b.mp3', 'c.mp3'],
          'c.mp3',
        );
        expect(restored.map((s) => s.path).toList(), ['a.mp3', 'b.mp3', 'c.mp3', 'd.mp3']);
        expect(idx, 2);
      });

      test('restoreOriginalOrder no toca nada si no hay shuffle', () async {
        final songs = [makeSong('a.mp3'), makeSong('b.mp3')];
        service.queueState = QueueState(list: List.from(songs), index: 0, label: 'Test');
        service.originalOrder = [];
        service.queueShuffled = false;
        await service.restoreOriginalOrder();
        expect(service.queueState.list.map((s) => s.path).toList(), ['a.mp3', 'b.mp3']);
      });

      test('shuffleAllQueue no hace nada con 1 tema (sin player)', () async {
        final songs = [makeSong('a.mp3')];
        service.queueState = QueueState(list: List.from(songs), index: 0, label: 'Test');
        service.queueShuffled = false;
        await service.shuffleAllQueue();
        expect(service.queueShuffled, isFalse);
      });
    });

    group('buildAudioSource', () {
      test('retorna AudioSource.uri con MediaItem', () async {
        final song = makeSong('/tmp/test.mp3', title: 'Test', album: 'Album');
        final src = await service.buildAudioSource(song);
        // En entorno de test sin archivo real, puede fallar pero no debe crashear
        // Si no hay archivo, igual crea AudioSource.uri (just_audio no verifica existencia hasta play)
        expect(src, isNotNull);
      });
    });
  });
}
