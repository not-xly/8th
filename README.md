# 8th

Reproductor de música local para Android hecho con Flutter. Sin streaming, sin cuentas - solo tus archivos.

![Flutter](https://img.shields.io/badge/Flutter-3.47-blue) ![Android](https://img.shields.io/badge/Android-API24%2B-green) ![License](https://img.shields.io/badge/License-MIT-yellow)

> Solo Android: aunque el repo conserva las carpetas generadas por Flutter (`ios/`, `web/`, `windows/`, `macos/`, `linux/`), la lectura de biblioteca (`MediaStore`/`SAF`), la notificación multimedia y Android Auto usan código nativo (`MethodChannel`/`CarAppService`). No se borran esas carpetas para no romper `flutter create`/`build`, pero no hay soporte de escritorio ni iOS.

## Características

- **Biblioteca por álbumes** con portadas extraídas de metadatos (`MediaStore` / `SAF` + `MediaMetadataRetriever`)
- **Grid adaptativo** - 5 columnas en móvil vertical, hasta 16 en tablet/horizontal (`album_grid_view.dart:34`)
- **Selección de carpeta** (`SAF`) o archivos sueltos, persistida con `shared_preferences`
- **Cola editable** - añadir a continuación/al final, eliminar, reordenar (drag & drop) y aleatorizar sin cortar reproducción
- **Búsqueda** por título, artista o álbum + orden original restaurable
- **Reproducción en segundo plano** con notificación multimedia (`just_audio` + `audio_service` + `AudioHandler`)
- **Letras sincronizadas** (`lrclib.net` / `music.163.com` / `lyrics.ovh`, cache en `8th_lrc/`) con vista `LRC` y sincronización en `FullPlayer`
- **Tema oscuro + color dinámico** extraído de la portada (`palette_generator`)
- **Android Auto** - `CarAppService` (`template` + `media`) con lista con carátulas, `PlaybackScreen` y cola. Testeado con `Desktop Head Unit` (`DHU`)

## Stack

`Flutter` · `Dart` · `just_audio` · `audio_service` · `audio_metadata_reader` · `palette_generator` · `Kotlin` (`MainActivity` + `auto/` para `MediaStore`/`SAF`/`CarApp`)

## Arquitectura

```
lib/src/
  controllers/music_player_controller.dart  # estado + cola
  services/
    audio_player_service.dart               # just_audio + queue + loop/shuffle
    library_service.dart                    # MediaStore/SAF → SongData
    lyrics_service.dart                     # fetch + cache LRC
  screens/
    music_player_screen.dart                # grid + búsqueda + cola
    full_player_screen.dart                 # player + letras
  widgets/album_grid_view.dart              # grid 5-16 cols + reorder iOS-style
  theme/palette_service.dart                # color dinámico
android/app/src/main/kotlin/com/xly/u_8th/auto/
  EighthCarAppService.kt · LibraryProvider.kt · LibraryScreen.kt
  AlbumsScreen.kt (lista con carátula) · PlaybackScreen.kt · QueueScreen.kt
```

## Desarrollo

```sh
flutter pub get
flutter analyze   # 0 issues
flutter test      # 70 tests
flutter run
```

### Android Auto (DHU)

```sh
# 1. Activa modo desarrollador en Android Auto (toca 10x Versión)
# 2. ⋮ > Ajustes de desarrollador > Fuentes desconocidas ON
# 3. ⋮ > Iniciar servidor de unidad principal
adb forward tcp:5277 tcp:5277
/Users/xly/Library/Android/sdk/extras/google/auto/desktop-head-unit
```

Requiere `android/app/src/main/res/xml/automotive_app_desc.xml` con `media` + `template` y `androidx.car.app.minCarApiLevel` en `AndroidManifest.xml`.

## Licencia

MIT - ver [LICENSE](LICENSE)
