<p align="center">
  <img src="8th.jpeg" width="160" alt="Logo de 8th">
</p>

<h1 align="center">8th</h1>

<p align="center">
  Reproductor de música local para Android, escrito 100% en Kotlin.<br>
  Sin streaming, sin cuentas: tu música, tal cual está en tu teléfono.
</p>

<p align="center">
  <a href="https://github.com/not-xly/8th/releases/latest/download/8th.apk">
    <img src="https://img.shields.io/badge/Descargar-APK-red?style=for-the-badge&logo=android&logoColor=white" alt="Descargar APK">
  </a>
</p>

<p align="center"><sub>Al instalar, Android pedirá permiso para "instalar apps desconocidas" (desde el navegador o el gestor de archivos). Es normal: la APK va firmada por el autor y se distribuye fuera de Google Play.</sub></p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-API%2024%2B-green" alt="Android API 24+">
  <img src="https://img.shields.io/badge/Kotlin-100%25-7F52FF" alt="Kotlin">
  <img src="https://img.shields.io/badge/License-MIT-yellow" alt="MIT">
</p>

---

## Características

**Biblioteca**
- Vista por álbumes con portadas extraídas de los metadatos, con caché en disco.
- Artistas, incluidas las colaboraciones (`feat.`, `&`, etc.).
- Rejilla adaptativa: 5 columnas en móvil y hasta 16 en tablet o en horizontal. El orden se guarda.
- Carpeta de música (SAF) o archivos sueltos, recordados entre sesiones.

**Búsqueda**
- Canciones, artistas y álbumes en una sola pantalla, insensible a mayúsculas y tildes.
- Los artistas aparecen primero, con su recuento: `X álbumes + N canciones` (N son las canciones fuera de esos álbumes).
- Búsqueda dentro de las letras ya descargadas: escribe una frase y encuentra la canción.

**Artista**
- Ficha con todos sus álbumes y una fila "Fuera de álbumes" para las canciones sueltas.
- Reproducir o reproducir en aleatorio todo lo del artista.

**Reproducción y cola**
- Reproducción en segundo plano con notificación multimedia (Media3 + `MediaSessionService`).
- Cola editable: añadir a continuación o al final, quitar, reordenar y aleatorizar sin cortar la reproducción.
- Playlists propias.

**Letras**
- Sincronizadas, con resaltado de la línea actual y desplazamiento automático.
- Ajuste fino de sincronía (±60 s), importar `.lrc` y edición manual.
- Caché local en `8th_lrc/`: una vez descargadas, funcionan sin conexión.

**Aspecto**
- Tema oscuro con color dinámico tomado de la portada de la canción en reproducción.

**Android Auto**
- Biblioteca, álbumes con carátulas, todas las canciones, cola y "aleatorio total" (`androidx.car.app`).

## Privacidad

- Tu música no sale del dispositivo. No hay cuentas ni analítica propia.
- Para buscar letras, la app envía artista y título a [lrclib.net](https://lrclib.net), `music.163.com` y [lyrics.ovh](https://lyrics.ovh). Puedes trabajar sin ellas: las letras guardadas siguen disponibles.

## Stack

`Kotlin` · `Jetpack Compose` · `Media3 (ExoPlayer + MediaSession)` · `androidx.car.app` · `Coroutines` · `OkHttp`

## Estructura

```
app/src/main/kotlin/com/xly/u_8th/
├── data/        # Biblioteca (MediaStore/SAF), artistas, preferencias,
│                # caché de portadas, letras (.lrc) y su búsqueda
├── domain/      # Modelos (Song, Playlist, LoopMode)
├── playback/    # Servicio Media3, cola, conexión del reproductor,
│                # estado compartido con Android Auto
├── auto/        # Android Auto: CarAppService y pantallas de plantillas
├── ui/          # Compose: tema, componentes, pantallas y ViewModel
└── util/        # Normalización de búsqueda, formato, hash estable
```

## Requisitos

- Android 7.0 (API 24) o superior.
- JDK 17 y el Android SDK.

## Compilar

```sh
./gradlew :app:installDebug       # compilar e instalar en el dispositivo
./gradlew :app:testDebugUnitTest  # pruebas unitarias
```

### Probar Android Auto (Desktop Head Unit)

1. En el teléfono, activa el modo desarrollador de Android Auto (toca 10 veces "Versión").
2. En Android Auto: ⋮ › Ajustes de desarrollador › activa "Fuentes desconocidas".
3. ⋮ › "Iniciar servidor de unidad principal".
4. En el equipo:

```sh
adb forward tcp:5277 tcp:5277
"$ANDROID_HOME/extras/google/auto/desktop-head-unit"
```

## Licencia

MIT. Ver [LICENSE](LICENSE).
