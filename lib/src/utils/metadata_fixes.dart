/// Correcciones manuales de metadatos mal etiquetados de origen.
///
/// Los tags vienen de los ficheros (MediaStore o lectura directa) y a veces
/// traen errores conocidos que ningún parseo puede adivinar. Tabla explícita
/// y conservadora: solo corrige el caso exacto, nunca toca tags correctos.
class MetadataFixes {
  /// Álbum (minúsculas) → artista correcto.
  static const Map<String, String> _artistByAlbum = {
    // El dúo es ¥$, pero en la biblioteca debe colgar de Kanye West.
    'vultures 1': 'Kanye West',
    'vultures 2': 'Kanye West',
  };

  /// ¿Es el tag erróneo del dúo ("¥$", a veces leído como Y$/YS/¥S)?
  static bool _isYsTag(String artist) {
    final n = artist.trim().toLowerCase().replaceAll(RegExp(r'\s+'), '');
    return n == '¥\$' ||
        n == 'y\$' ||
        n == 'ys' ||
        n == '¥s' ||
        n == '¥' ||
        n == '\$';
  }

  /// Devuelve el artista corregido para [album]+[artist], o [artist] tal cual
  /// si no hay corrección aplicable. Solo sustituye cuando el tag actual es
  /// el erróneo conocido (o vacío); un tag ya correcto se respeta.
  static String? artistFor({required String? album, required String? artist}) {
    if (album == null) return artist;
    final fixed = _artistByAlbum[album.trim().toLowerCase()];
    if (fixed == null) return artist;
    final current = (artist ?? '').trim();
    if (current.isEmpty || _isYsTag(current)) return fixed;
    return artist;
  }
}
