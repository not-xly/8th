package com.xly.u_8th.auto

import android.content.Context
import com.xly.u_8th.EighthApplication
import com.xly.u_8th.domain.Song
import com.xly.u_8th.playback.PlaybackCatalog
import com.xly.u_8th.playback.PlayerBus

/**
 * Car screens -> playback routing. Native-to-native now: instead of an
 * in-process session bridge: commands go straight to the media3 session via
 * [PlayerConnection] and state reaches the car through AutoPlaybackState
 * (written by AutoStateSync), so the old pending/deliver machinery is gone.
 */
object AutoBridge {

    /**
     * Límite del host Car App para `ItemList`: `ListTemplate` **trunca en
     * silencio** a 100 ítems (no lanza excepción). Las pantallas paginadas
     * reservan un hueco para su fila de "siguiente página" (`MAX_ITEMS - 1`).
     */
    const val MAX_ITEMS = 100

    fun requestPlayQueue(context: Context, tracks: List<Track>, startIndex: Int) {
        // La pantalla solo muestra MAX_ITEMS, así que el índice tapped cae
        // dentro de lo enviado; el corte es solo una red de seguridad para
        // el binder (TransactionTooLarge con bibliotecas grandes).
        val limited = tracks.take(MAX_ITEMS)
        if (limited.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, limited.size - 1)
        // El catálogo conserva la canción completa (portada, filePath) si el
        // móvil ya la registró; si no, se reconstruye desde la pista.
        val songs = limited.map { t ->
            PlaybackCatalog.get(t.uri) ?: Song(
                path = t.uri,
                contentUri = t.uri,
                title = t.title,
                artist = t.artist,
                album = t.album,
                durationMs = t.durationMs,
            )
        }
        PlaybackCatalog.putAll(songs)
        // Vía la Application: `PlayerConnection.get` es singleton de primer
        // escritor, y desde el coche se resolvería con un scope anónimo
        // descartable en lugar del scope de la app.
        val connection = EighthApplication.get(context).playerConnection
        connection.launch {
            playQueue(
                songs,
                initialIndex = safeIndex,
                // safeIndex ya está acotado a `limited`: indexarlo es seguro.
                label = songs[safeIndex].album ?: "Auto",
                autoPlay = true,
            )
        }
    }

    fun sendControl(context: Context, action: String) {
        EighthApplication.get(context).playerConnection.launch {
            when (action) {
                "play" -> play()
                "pause" -> pause()
                "next" -> next()
                "prev" -> prev()
                "cycleLoop" -> cycleLoop()
                // Current flag lives in PlayerBus (written by the service).
                "toggleShuffle" -> setShuffled(!PlayerBus.shuffleEnabled.value)
                // "Aleatorio total": service-side shuffle of the whole
                // library without shipping 5000 tracks over the session.
                "shuffleAll" -> shuffleAll()
            }
        }
    }

    fun sendQueueAction(context: Context, command: String, index: Int = -1) {
        if (command != "playIndex" || index < 0) return
        EighthApplication.get(context).playerConnection.launch { seekToIndex(index) }
    }
}
