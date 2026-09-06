package com.xly.u_8th.auto

import android.content.Context
import com.xly.u_8th.domain.Song
import com.xly.u_8th.playback.PlaybackCatalog
import com.xly.u_8th.playback.PlayerBus
import com.xly.u_8th.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Car screens -> playback routing. Native-to-native now: instead of an
 * EventChannel into Flutter, commands go straight to the media3 session via
 * [PlayerConnection] and state reaches the car through AutoPlaybackState
 * (written by AutoStateSync), so the old pending/deliver machinery is gone.
 */
object AutoBridge {

    // Límite del host Car App Library para ItemList (~100 ítems): más
    // lanza IllegalArgumentException y deja pantalla negra.
    const val MAX_ITEMS = 100

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun requestPlayQueue(context: Context, tracks: List<Track>, startIndex: Int) {
        // Trunca al límite del host: la pantalla ya solo muestra MAX_ITEMS,
        // así que el índice siempre cae dentro de lo enviado.
        val limited = tracks.take(MAX_ITEMS)
        if (limited.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, limited.size - 1)
        val songs = limited.map { t ->
            Song(
                path = t.uri,
                contentUri = t.uri,
                title = t.title,
                artist = t.artist,
                album = t.album,
                durationMs = t.durationMs,
            )
        }
        PlaybackCatalog.putAll(songs)
        val connection = PlayerConnection.get(context)
        connection.launch {
            playQueue(
                songs,
                initialIndex = safeIndex,
                label = songs.getOrNull(safeIndex)?.album ?: "Auto",
                autoPlay = true,
            )
        }
    }

    fun sendControl(context: Context, action: String) {
        val connection = PlayerConnection.get(context)
        connection.launch {
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
        PlayerConnection.get(context).launch { seekToIndex(index) }
    }
}
