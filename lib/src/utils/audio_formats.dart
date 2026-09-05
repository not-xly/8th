/// Extensiones de audio reconocidas: fuente única compartida por
/// LibraryService y el parseo de metadatos (antes duplicado en 2 sitios y
/// añadir un formato requería tocarlos todos).
const Set<String> audioExtensions = {
  'mp3',
  'wav',
  'flac',
  'aac',
  'm4a',
  'ogg',
  'wma',
  'opus'
};

/// Textos de UI centralizados (antes 'Desconocido' repetido en 6+ sitios).
class AppStrings {
  static const unknown = 'Desconocido';
  static const untitled = 'Sin título';
}
