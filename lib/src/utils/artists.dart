import '../models/song_data.dart';

/// Utilidades compartidas para parsear artistas.
///
/// Problema que resuelven: el tag `artist` suele traer varios artistas
/// ("Queen, David Bowie", "Eminem feat. Rihanna") pero a veces la coma es
/// parte del nombre ("Tyler, The Creator"). Cada pantalla partía a su manera
/// (el contador por comas, el menú de artista por igualdad exacta), así que
/// el menú no mostraba todos los álbumes que debía.
class Artists {
  /// Separadores entre artistas distintos: coma, punto y coma, barra,
  /// ampersand y coletillas (feat./ft./featuring/with/vs.).
  /// "Tyler, The Creator" se protege antes de partir (ver [parseArtists]).
  // Ojo: tras "feat." no vale `\b` (entre "." y " " no hay frontera de
  // palabra y el punto quedaba colgado en el siguiente artista). Se usa
  // `(?!\w)` en su lugar.
  static final RegExp splitPattern = RegExp(
    r'\s*(?:,|;|/|&|\bfeat\.?(?!\w)|\bft\.?(?!\w)|\bfeaturing\b|\bwith\b|\bvs\.?(?!\w))\s*',
    caseSensitive: false,
  );

  static final RegExp _tyler = RegExp(
    r'tyler\s*,\s*the\s+creator',
    caseSensitive: false,
  );
  static const String _tylerPlaceholder = 'Tyler\x00The Creator';
  static const String tylerCanonical = 'Tyler, The Creator';

  /// Parte `raw` en artistas individuales (forma visible, sin duplicados).
  /// - "Queen, David Bowie" → ["Queen", "David Bowie"]
  /// - "Eminem feat. Rihanna" → ["Eminem", "Rihanna"]
  /// - "Tyler, The Creator" → ["Tyler, The Creator"]
  /// - null/vacío → []
  static List<String> parseArtists(String? raw) {
    if (raw == null) return const [];
    final trimmed = raw.trim();
    if (trimmed.isEmpty) return const [];
    final protected = trimmed.replaceAllMapped(_tyler, (_) => _tylerPlaceholder);
    final out = <String>[];
    final seen = <String>{};
    for (final part in protected.split(splitPattern)) {
      var p = part.trim().replaceAll('\x00', ', ');
      // Colapsa espacios internos ("Tyler,  The   Creator" → canónico).
      p = p.replaceAll(RegExp(r'\s+'), ' ');
      if (p.isEmpty) continue;
      final key = p.toLowerCase();
      if (seen.add(key)) out.add(p);
    }
    return out;
  }

  /// Normaliza para comparar ("  QUEEN " → "queen").
  static String normalize(String s) => s.trim().toLowerCase();

  /// Artista principal: el primero ("A, B & C feat. D" → "A").
  /// Respeta "Tyler, The Creator" (antes lo cortaba a "Tyler").
  static String? primaryArtist(String? raw) {
    final parts = parseArtists(raw);
    return parts.isEmpty ? null : parts.first;
  }

  /// ¿El tema incluye a [artist] entre sus artistas?
  static bool songHasArtist(SongData song, String artist) {
    final want = normalize(artist);
    if (want.isEmpty) return false;
    return parseArtists(song.artist).any((a) => normalize(a) == want);
  }

  /// ¿El tema incluye a alguno de [artists]?
  static bool songHasAnyArtist(SongData song, Iterable<String> artists) {
    final want = artists.map(normalize).where((a) => a.isNotEmpty).toSet();
    if (want.isEmpty) return false;
    return parseArtists(song.artist).any((a) => want.contains(normalize(a)));
  }

  /// Artista del álbum: el principal del primer tema con artista.
  /// El álbum "es de" quien abre el disco; un feat. del tema 5 no lo mueve
  /// de menú ni decide su portada cacheada.
  static String? albumArtistFor(List<SongData> songs) {
    for (final s in songs) {
      final p = primaryArtist(s.artist);
      if (p != null) return p;
    }
    return null;
  }

  /// ¿El álbum pertenece al menú de [artist]? Solo si el artista es el
  /// principal del primer tema (no basta con colaborar en otro corte).
  static bool albumMatchesArtist(
      List<SongData> albumSongs, Iterable<String> wanted) {
    if (albumSongs.isEmpty) return false;
    final first = primaryArtist(albumSongs.first.artist);
    if (first == null) return false;
    final f = normalize(first);
    return wanted.map(normalize).contains(f);
  }
}
