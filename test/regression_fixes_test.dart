import 'package:flutter_test/flutter_test.dart';
import 'package:eighth/src/controllers/music_player_controller.dart';
import 'package:eighth/src/models/song_data.dart';
import 'package:eighth/src/services/library_service.dart';
import 'package:eighth/src/services/lyrics_service.dart';
import 'package:eighth/src/utils/artists.dart';
import 'package:eighth/src/utils/formatters.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  // just_audio 0.10 toca AudioSession/MethodChannel en el constructor de
  // AudioPlayer: sin binding inicializado, crear el controller revienta.
  TestWidgetsFlutterBinding.ensureInitialized();

  group('Fix 5: hash estable para .lrc', () {
    test('mismo input -> mismo hash (estable entre reinicios)', () {
      expect(
        LyricsService.stableHash('content://media/1'),
        LyricsService.stableHash('content://media/1'),
      );
    });

    test('inputs distintos -> hashes distintos', () {
      expect(
        LyricsService.stableHash('/music/a.mp3'),
        isNot(LyricsService.stableHash('/music/b.mp3')),
      );
    });
  });

  group('Fix 7/8: extensiones + playback sin IO bloqueante', () {
    test('opus está soportado', () {
      expect(LibraryService.audioExtensions, contains('opus'));
    });

    test('parseMetadata filtra inexistentes (sin fantasmas)', () {
      final songs = parseMetadata(['/tmp/no_existe_12345.opus']);
      expect(songs, isEmpty);
    });

    test('playbackUri prefiere contentUri estable sin tocar disco', () {
      final s = SongData(
        path: 'content://media/1',
        contentUri: 'content://media/1',
        filePath: '/tmp/no_existe_12345.mp3',
      );
      // No debe lanzar aunque el fichero no exista (antes hacía existsSync).
      // contentUri primero: sobrevive a movimientos del fichero.
      expect(s.playbackUri, isA<Uri>());
      expect(s.playbackUri.toString(), 'content://media/1');
    });

    test('playbackUri usa filePath cuando no hay contentUri', () {
      final s = SongData(
        path: '/music/a.mp3',
        filePath: '/tmp/no_existe_12345.mp3',
      );
      expect(s.playbackUri.path, '/tmp/no_existe_12345.mp3');
    });

    test('displayTitle no muestra IDs numéricos de content://', () {
      final s = SongData(path: 'content://media/external/audio/media/123');
      expect(s.displayTitle, 'Sin título');
    });

    test('Greatest Hits de dos artistas no se fusionan', () {
      final service = LibraryService();
      final songs = [
        SongData(path: 'a.mp3', album: 'Greatest Hits', artist: 'Queen'),
        SongData(path: 'b.mp3', album: 'Greatest Hits', artist: 'ABBA'),
      ];
      final grouped = service.groupByAlbum(songs);
      expect(grouped.keys.length, 2);
    });
  });

  group('Fix artista: conteo normalizado (feat. no duplica)', () {
    setUp(() {
      SharedPreferences.setMockInitialValues({});
    });

    test('"A, B" cuenta 2', () {
      final c = MusicPlayerController();
      addTearDown(c.dispose);
      c.songs = [SongData(path: 'a.mp3', artist: 'Queen, David Bowie')];
      expect(c.countUniqueArtists(), 2);
    });

    test('feat./ft. no duplican al principal ni al colaborador', () {
      final c = MusicPlayerController();
      addTearDown(c.dispose);
      c.songs = [
        SongData(path: 'a.mp3', artist: 'Eminem'),
        SongData(path: 'b.mp3', artist: 'Eminem feat. Rihanna'),
        SongData(path: 'c.mp3', artist: 'eminem ft. Rihanna'),
      ];
      expect(c.countUniqueArtists(), 2);
    });

    test('sin artista no cuenta', () {
      final c = MusicPlayerController();
      addTearDown(c.dispose);
      c.songs = [SongData(path: 'a.mp3')];
      expect(c.countUniqueArtists(), 0);
    });
  });

  group('Fix 14: formato de duración con horas', () {
    test('minutos y segundos', () {
      expect(fmtMs(65000), '1:05');
    });

    test('con horas (mixes, clásica)', () {
      expect(fmtMs(3723000), '1:02:03');
    });

    test('cero', () {
      expect(fmtMs(0), '0:00');
    });
  });

  group('Fix menú de artista: parseo compartido', () {
    test('"Tyler, The Creator" es un solo artista', () {
      expect(Artists.parseArtists('Tyler, The Creator'), ['Tyler, The Creator']);
      expect(Artists.primaryArtist('Tyler, The Creator'), 'Tyler, The Creator');
    });

    test('coma separa artistas distintos', () {
      expect(Artists.parseArtists('Queen, David Bowie'), ['Queen', 'David Bowie']);
    });

    test('feat. separa pero el principal es el primero', () {
      expect(Artists.parseArtists('Eminem feat. Rihanna'), ['Eminem', 'Rihanna']);
      expect(Artists.primaryArtist('Eminem feat. Rihanna'), 'Eminem');
    });

    test('songHasArtist encuentra colaboradores', () {
      final s = SongData(path: 'a.mp3', artist: 'Queen, David Bowie');
      expect(Artists.songHasArtist(s, 'Queen'), isTrue);
      expect(Artists.songHasArtist(s, 'David Bowie'), isTrue);
      expect(Artists.songHasArtist(s, 'ABBA'), isFalse);
    });

    test('songHasArtist respeta a Tyler', () {
      final s = SongData(path: 'a.mp3', artist: 'Tyler, The Creator');
      expect(Artists.songHasArtist(s, 'Tyler, The Creator'), isTrue);
      expect(Artists.songHasArtist(s, 'Tyler'), isFalse);
    });

    test('albumArtistFor es el principal del primer tema', () {
      final songs = [
        SongData(path: 'a.mp3', artist: 'Eminem feat. Rihanna'),
        SongData(path: 'b.mp3', artist: 'Eminem'),
        SongData(path: 'c.mp3', artist: 'Eminem'),
      ];
      expect(Artists.albumArtistFor(songs), 'Eminem');
      // ...aunque la mayoría sea otra: manda quien abre el disco.
      final songs2 = [
        SongData(path: 'a.mp3', artist: 'Rihanna feat. Eminem'),
        SongData(path: 'b.mp3', artist: 'Eminem'),
        SongData(path: 'c.mp3', artist: 'Eminem'),
      ];
      expect(Artists.albumArtistFor(songs2), 'Rihanna');
    });

    test('el álbum solo sale en el menú del artista del primer tema', () {
      final album = [
        SongData(path: 'a.mp3', artist: 'Eminem'),
        SongData(path: 'b.mp3', artist: 'Eminem feat. Rihanna'),
      ];
      expect(Artists.albumMatchesArtist(album, ['Eminem']), isTrue);
      // Rihanna colabora en el corte 2: el álbum NO es suyo.
      expect(Artists.albumMatchesArtist(album, ['Rihanna']), isFalse);
      expect(Artists.albumMatchesArtist(album, ['Tyler, The Creator']), isFalse);
    });

    test('VULTURES 1/2 cuelgan de Kanye West, no de ¥\$', () {
      final service = LibraryService();
      final songs = service.fromRichList([
        {'uri': 'content://1', 'name': 'a.mp3', 'album': 'VULTURES 1', 'artist': '¥\$'},
        {'uri': 'content://2', 'name': 'b.mp3', 'album': 'VULTURES 2', 'artist': 'YS'},
      ]);
      expect(songs[0].artist, 'Kanye West');
      expect(songs[1].artist, 'Kanye West');
      // Un tag ya correcto no se toca, ni otros álbumes.
      final ok = service.fromRichList([
        {'uri': 'content://3', 'name': 'c.mp3', 'album': 'VULTURES 1', 'artist': 'Kanye West'},
        {'uri': 'content://5', 'name': 'e.mp3', 'album': 'Otro', 'artist': '¥\$'},
      ]);
      expect(ok[0].artist, 'Kanye West');
      expect(ok[1].artist, '¥\$');
    });

    test('Tyler cuenta 1 en el diálogo de info', () {
      final c = MusicPlayerController();
      addTearDown(c.dispose);
      c.songs = [SongData(path: 'a.mp3', artist: 'Tyler, The Creator')];
      expect(c.countUniqueArtists(), 1);
    });

    test('feats del mismo álbum no lo fragmentan', () {
      final service = LibraryService();
      final grouped = service.groupByAlbum([
        SongData(path: 'a.mp3', album: 'Recovery', artist: 'Eminem', trackNumber: 1),
        SongData(path: 'b.mp3', album: 'Recovery', artist: 'Eminem feat. Rihanna', trackNumber: 2),
        SongData(path: 'c.mp3', album: 'Recovery', artist: 'Eminem', trackNumber: 3),
      ]);
      expect(grouped.keys.toList(), ['Recovery']);
      expect(grouped['Recovery']!.length, 3);
    });

    test('mismo nombre, artistas distintos 1-1, sí se divide', () {
      final service = LibraryService();
      final grouped = service.groupByAlbum([
        SongData(path: 'a.mp3', album: 'Hits', artist: 'Queen'),
        SongData(path: 'b.mp3', album: 'Hits', artist: 'ABBA'),
      ]);
      expect(grouped.keys.length, 2);
    });

    test('cleanArtist no corta a Tyler (letras)', () {
      final lyrics = LyricsService();
      expect(lyrics.cleanArtist('Tyler, The Creator'), 'Tyler, The Creator');
      expect(lyrics.cleanArtist('A, B & C feat. D'), 'A');
    });
  });
}
