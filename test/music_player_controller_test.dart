import 'package:flutter_test/flutter_test.dart';
import 'package:eighth/src/controllers/music_player_controller.dart';
import 'package:eighth/src/models/song_data.dart';
import 'package:eighth/src/services/library_service.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  SharedPreferences.setMockInitialValues({});

  group('MusicPlayerController', () {
    late MusicPlayerController controller;

    setUp(() {
      controller = MusicPlayerController();
    });

    tearDown(() {
      controller.dispose();
    });

    test('estado inicial', () {
      expect(controller.songs, isEmpty);
      expect(controller.grouped, isEmpty);
      expect(controller.initialized, isFalse);
      expect(controller.loading, isFalse);
      expect(controller.searching, isFalse);
      expect(controller.currentTab, ViewTab.albums);
    });

    test('toggleAlbum añade y quita', () {
      controller.toggleAlbum('Album A');
      expect(controller.selectedAlbums.contains('Album A'), isTrue);
      controller.toggleAlbum('Album A');
      expect(controller.selectedAlbums.contains('Album A'), isFalse);
    });

    test('setTab cambia currentTab y notifica', () {
      var notified = false;
      controller.addListener(() => notified = true);
      controller.setTab(ViewTab.queue);
      expect(controller.currentTab, ViewTab.queue);
      expect(notified, isTrue);
    });

    test('startSearch / closeSearch / updateSearchQuery', () {
      controller.startSearch();
      expect(controller.searching, isTrue);
      controller.updateSearchQuery('beatles');
      expect(controller.searchQuery, 'beatles');
      controller.closeSearch();
      expect(controller.searching, isFalse);
      expect(controller.searchQuery, isEmpty);
    });

    test('searchResults filtra por título, artista y álbum', () {
      controller.songs = [
        SongData(path: 'a.mp3', title: 'Hey Jude', artist: 'Beatles', album: 'White'),
        SongData(path: 'b.mp3', title: 'Bohemian', artist: 'Queen', album: 'Night'),
        SongData(path: 'c.mp3', title: 'Yesterday', artist: 'Beatles', album: 'Help'),
      ];
      controller.grouped = LibraryService().groupByAlbum(controller.songs);
      // Buscar por título
      var res = controller.searchResults('jude');
      expect(res.length, 1);
      expect(res[0].value.title, 'Hey Jude');
      // Buscar por artista
      res = controller.searchResults('queen');
      expect(res.length, 1);
      // Buscar por álbum
      res = controller.searchResults('white');
      expect(res.length, 1);
      // Sin query
      res = controller.searchResults('');
      expect(res, isEmpty);
    });

    test('sortedAlbumKeys respeta albumOrder del controller', () {
      controller.songs = [
        SongData(path: 'a.mp3', album: 'B'),
        SongData(path: 'b.mp3', album: 'A'),
        SongData(path: 'c.mp3', album: 'C'),
      ];
      controller.grouped = LibraryService().groupByAlbum(controller.songs);
      controller.albumOrder = ['C', 'A'];
      final sorted = controller.sortedAlbumKeys();
      expect(sorted, ['C', 'A', 'B']);
    });

    test('clearSelection limpia selectedAlbums', () {
      controller.selectedAlbums.addAll(['A', 'B']);
      controller.clearSelection();
      expect(controller.selectedAlbums, isEmpty);
    });

    test('setReordering cambia flag', () {
      controller.setReordering(true);
      expect(controller.reorderingAlbums, isTrue);
      controller.setReordering(false);
      expect(controller.reorderingAlbums, isFalse);
    });

    test('reorderAlbums actualiza orden', () async {
      controller.grouped = {
        'A': <SongData>[],
        'B': <SongData>[],
      };
      await controller.reorderAlbums(['B', 'A']);
      expect(controller.albumOrder, ['B', 'A']);
    });
  });
}
