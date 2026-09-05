import 'package:flutter_test/flutter_test.dart';
import 'package:eighth/src/models/song_data.dart';
import 'package:eighth/src/services/library_service.dart';

void main() {
  group('LibraryService - lógica pura', () {
    late LibraryService service;

    setUp(() {
      service = LibraryService();
    });

    group('resolveTrack', () {
      test('usa trackNumber si existe', () {
        final s = SongData(path: 'a.mp3', trackNumber: 5, title: '10 Otro');
        expect(service.resolveTrack(s), 5);
      });

      test('extrae número inicial del título si no hay trackNumber', () {
        final s = SongData(path: 'a.mp3', title: '03 - Intro');
        expect(service.resolveTrack(s), 3);
      });

      test('devuelve 999 si no hay número', () {
        final s = SongData(path: 'a.mp3', title: 'Intro');
        expect(service.resolveTrack(s), 999);
      });

      test('extrae número de path si no hay título', () {
        final s = SongData(path: '/music/07_track.mp3');
        expect(service.resolveTrack(s), 7);
      });
    });

    group('groupByAlbum', () {
      test('agrupa por álbum y ordena por track', () {
        final songs = [
          SongData(path: 'c.mp3', album: 'Album B', trackNumber: 2, title: 'B'),
          SongData(path: 'b.mp3', album: 'Album B', trackNumber: 1, title: 'A'),
          SongData(path: 'a.mp3', album: 'Album A', trackNumber: 1, title: 'A'),
          SongData(path: 'd.mp3', album: null, title: 'Sin album'),
        ];
        final grouped = service.groupByAlbum(songs);
        expect(grouped.keys.toList(), ['Album A', 'Album B', 'Desconocido']);
        expect(grouped['Album B']![0].trackNumber, 1);
        expect(grouped['Album B']![1].trackNumber, 2);
      });

      test('ordena alfabéticamente con Desconocido al final', () {
        final songs = [
          SongData(path: 'a.mp3', album: 'Zebra'),
          SongData(path: 'b.mp3', album: 'Alpha'),
          SongData(path: 'c.mp3', album: null),
        ];
        final grouped = service.groupByAlbum(songs);
        expect(grouped.keys.toList(), ['Alpha', 'Zebra', 'Desconocido']);
      });

      test('desempate por título si track igual', () {
        final songs = [
          SongData(path: 'b.mp3', album: 'X', trackNumber: 1, title: 'Zeta'),
          SongData(path: 'a.mp3', album: 'X', trackNumber: 1, title: 'Alpha'),
        ];
        final grouped = service.groupByAlbum(songs);
        expect(grouped['X']![0].title, 'Alpha');
      });
    });

    group('sortedAlbumKeys', () {
      test('respeta albumOrder y añade resto', () {
        final grouped = {
          'A': <SongData>[],
          'B': <SongData>[],
          'C': <SongData>[],
        };
        final order = ['C', 'A'];
        final sorted = service.sortedAlbumKeys(grouped, order);
        expect(sorted, ['C', 'A', 'B']);
      });

      test('ignora elementos de order que no existen', () {
        final grouped = {
          'A': <SongData>[],
          'B': <SongData>[],
        };
        final sorted = service.sortedAlbumKeys(grouped, ['X', 'A']);
        expect(sorted, ['A', 'B']);
      });
    });

    group('fromRichList', () {
      test('convierte lista rica de MediaStore a SongData', () {
        final rich = [
          {
            'uri': 'content://media/1',
            'name': 'song.mp3',
            'title': 'Mi Canción',
            'artist': 'Artista',
            'album': 'Álbum',
            'track': '3/12',
            'path': '/storage/song.mp3',
            'artUri': 'content://art/1',
            'duration': 180000,
          },
        ];
        final songs = service.fromRichList(rich);
        expect(songs.length, 1);
        expect(songs[0].title, 'Mi Canción');
        expect(songs[0].trackNumber, 3);
        expect(songs[0].durationMs, 180000);
        expect(songs[0].albumArtUri, 'content://art/1');
      });

      test('track como int', () {
        final rich = [
          {'uri': 'content://1', 'name': 'a.mp3', 'track': 5},
        ];
        final songs = service.fromRichList(rich);
        expect(songs[0].trackNumber, 5);
      });

      test('track 0 o vacío se convierte en null', () {
        final rich = [
          {'uri': 'content://1', 'name': 'a.mp3', 'track': 0},
          {'uri': 'content://2', 'name': 'b.mp3', 'track': ''},
        ];
        final songs = service.fromRichList(rich);
        expect(songs[0].trackNumber, isNull);
        expect(songs[1].trackNumber, isNull);
      });

      test('ignora entradas sin uri', () {
        final rich = [
          {'name': 'a.mp3'},
          {'uri': 'content://1', 'name': 'b.mp3'},
        ];
        final songs = service.fromRichList(rich);
        expect(songs.length, 1);
      });

      test('usa name como fallback de title', () {
        final rich = [
          {'uri': 'content://1', 'name': 'my_song.flac'},
        ];
        final songs = service.fromRichList(rich);
        expect(songs[0].title, 'my_song');
      });
    });
  });

  group('SongData', () {
    test('displayTitle usa título si existe', () {
      final s = SongData(path: '/a/b.mp3', title: '  Mi Título  ');
      expect(s.displayTitle, 'Mi Título');
    });

    test('displayTitle fallback a nombre de archivo', () {
      final s = SongData(path: '/music/my_track.flac');
      expect(s.displayTitle, 'my_track');
    });

    test('playUri prefiere contentUri', () {
      final s = SongData(path: '/a/b.mp3', contentUri: 'content://1');
      expect(s.playUri, 'content://1');
    });

    test('isContentUri detecta content://', () {
      final s = SongData(path: 'content://1', contentUri: 'content://1');
      expect(s.isContentUri, isTrue);
    });
  });

  group('parseMetadata (isolate)', () {
    test('filtra extensiones no soportadas', () {
      final songs = parseMetadata(['/tmp/a.txt', '/tmp/b.jpg']);
      expect(songs, isEmpty);
    });

    test('filtra paths inexistentes sin crash (sin fantasmas)', () {
      final songs = parseMetadata(['/tmp/no_existe_12345.mp3']);
      expect(songs, isEmpty);
    });

    test('content:// no se filtra por existsSync', () {
      final songs = parseMetadata(['content://media/external/audio/media/123']);
      expect(songs.length, 1);
    });
  });
}
