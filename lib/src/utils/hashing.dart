/// Hash FNV-1a compartido. Antes había 4 copias de esta función
/// (audio_player_service, library_service, lyrics_service, palette_service).
library;

/// FNV-1a 32-bit sobre un string. Estable entre ejecuciones (a diferencia
/// de `String.hashCode` de Dart, que cambia en cada arranque), por lo que
/// es válido para nombres de fichero y claves de caché persistentes.
int fnv1a(String s) {
  var hash = 0x811c9dc5;
  for (var i = 0; i < s.length; i++) {
    hash ^= s.codeUnitAt(i);
    hash = (hash * 0x01000193) & 0xFFFFFFFF;
  }
  return hash;
}

/// FNV-1a muestreado sobre bytes: en lugar de recorrer MBs enteros de una
/// portada en el hilo UI, hash de longitud + 512 muestras espaciadas.
int fnv1aBytesSampled(List<int> bytes, {int samples = 512}) {
  var hash = 0x811c9dc5;
  hash ^= bytes.length;
  hash = (hash * 0x01000193) & 0xFFFFFFFF;
  final step = (bytes.length / samples).ceil().clamp(1, 4096);
  for (var i = 0; i < bytes.length; i += step) {
    hash ^= bytes[i];
    hash = (hash * 0x01000193) & 0xFFFFFFFF;
  }
  return hash;
}

/// Hash hexadecimal recortado a [length] dígitos (para sufijos de nombre
/// de fichero anti-colisión).
String fnv1aHex(String s, [int length = 6]) =>
    fnv1a(s).toRadixString(16).padLeft(8, '0').substring(0, length);
