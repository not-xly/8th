# 8th

Reproductor de música local para Android, 100% nativo en Kotlin. Sin streaming, sin cuentas — solo tus archivos.

![Android](https://img.shields.io/badge/Android-API24%2B-green) ![License](https://img.shields.io/badge/License-MIT-yellow)

Interfaz en Jetpack Compose, reproducción con Media3/ExoPlayer y Android Auto con la librería `androidx.car.app`.

## Características

- **Biblioteca por álbumes** con portadas extraídas de metadatos (`MediaStore` / `SAF` + `MediaMetadataRetriever`) y caché en disco
- **Grid adaptativo** — 5 columnas en móvil vertical, hasta 16 en tablet/horizontal, con reordenamiento por arrastre y orden persistente
- **Selección de carpeta** (`SAF`) o archivos sueltos, persistida entre sesiones (incluye migración de datos de versiones anteriores)
- **Cola editable** — añadir a continuación/al final, eliminar, reordenar en vivo y aleatorizar sin cortar la reproducción
- **Búsqueda** por título, artista o álbum (insensible a mayúsculas y tildes)
- **Reproducción en segundo plano** con notificación multimedia (Media3 + `MediaSessionService`)
- **Letras sincronizadas** (`lrclib.net` / `music.163.com` / `lyrics.ovh`, caché en `8th_lrc/`) con resaltado, auto-scroll y sincronización masiva
- **Tema oscuro + color dinámico** extraído de la portada del tema actual
- **Android Auto** — biblioteca, álbumes con carátulas, todas las canciones, cola y "aleatorio total" (`CarAppService`)

## Stack

`Kotlin` · `Jetpack Compose` · `Media3 (ExoPlayer + MediaSession)` · `androidx.car.app` · `Coroutines` · `OkHttp`

## Estructura

```
app/src/main/kotlin/com/xly/u_8th/
├── data/        # Biblioteca (MediaStore/SAF), preferencias + migración,
│                # caché de portadas, letras (.lrc)
├── domain/      # Modelos (Song, LoopMode)
├── playback/    # Servicio Media3, gestor de cola, conexión del
│                # reproductor, estado compartido para Auto
├── auto/        # Android Auto: CarAppService y pantallas de plantillas
├── ui/          # Compose: tema, componentes, pantallas y ViewModel
└── util/        # Hash estable, formato, búsqueda
```

## Desarrollo

```sh
./gradlew :app:installDebug      # compilar e instalar
./gradlew :app:testDebugUnitTest # pruebas unitarias
```

### Android Auto (DHU)

```sh
# 1. Activa modo desarrollador en Android Auto (toca 10x Versión)
# 2. ⋮ > Ajustes de desarrollador > Fuentes desconocidas ON
# 3. ⋮ > Iniciar servidor de unidad principal
adb forward tcp:5277 tcp:5277
/Users/xly/Library/Android/sdk/extras/google/auto/desktop-head-unit
```

## Licencia

MIT — ver [LICENSE](LICENSE)
