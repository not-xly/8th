package com.xly.u_8th.auto

import android.os.Handler
import android.os.Looper

object AutoBridge {

    // Límite del host Car App Library para ItemList (~100 ítems): más
    // lanza IllegalArgumentException y deja pantalla negra. También evita
    // TransactionTooLarge (>1MB) en el EventChannel con bibliotecas grandes.
    const val MAX_ITEMS = 100

    private const val MAX_PENDING = 8

    @Volatile
    private var eventSink: ((Map<String, Any?>) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Si Dart aún no escucha (init tardío), los taps del coche se perdían.
    // Cola acotada (antes solo sobrevivía el último): dos taps rápidos
    // ("play" y luego "next") ya no descartan el primero.
    private val pending = ArrayDeque<Map<String, Any?>>()

    private fun enqueuePending(payload: Map<String, Any?>) {
        synchronized(pending) {
            pending.addLast(payload)
            while (pending.size > MAX_PENDING) pending.removeFirst()
        }
    }

    fun attach(sink: (Map<String, Any?>) -> Unit) {
        this.eventSink = sink
        val queued = synchronized(pending) {
            val copy = pending.toList()
            pending.clear()
            copy
        }
        if (queued.isNotEmpty()) {
            mainHandler.post {
                for (payload in queued) eventSink?.invoke(payload)
            }
        }
    }

    fun detach() {
        this.eventSink = null
    }

    @Suppress("UNUSED_PARAMETER")
    fun requestPlayQueue(context: android.content.Context, tracks: List<Track>, startIndex: Int) {
        // Trunca al límite del host: la pantalla ya solo muestra MAX_ITEMS,
        // así que el índice siempre cae dentro de lo enviado.
        val limited = tracks.take(MAX_ITEMS)
        val safeIndex = startIndex.coerceIn(0, (limited.size - 1).coerceAtLeast(0))
        val payload = mapOf(
            "action" to "playQueue",
            "index" to safeIndex,
            "tracks" to limited.map { t ->
                mapOf(
                    "id" to t.id,
                    "uri" to t.uri,
                    "title" to t.title,
                    "artist" to t.artist,
                    "album" to t.album,
                    "albumId" to t.albumId,
                    "durationMs" to t.durationMs,
                    "trackNumber" to t.trackNumber
                )
            }
        )
        deliver(payload)
    }

    @Suppress("UNUSED_PARAMETER")
    fun sendControl(context: android.content.Context, action: String) {
        val payload = mapOf("action" to "control", "command" to action)
        deliver(payload)
    }

    @Suppress("UNUSED_PARAMETER")
    fun sendQueueAction(context: android.content.Context, command: String, index: Int = -1) {
        val payload = mutableMapOf<String, Any?>(
            "action" to "queue",
            "command" to command
        )
        if (index >= 0) payload["index"] = index
        deliver(payload)
    }

    private fun deliver(payload: Map<String, Any?>) {
        if (eventSink == null) {
            enqueuePending(payload)
            return
        }
        mainHandler.post {
            val sink = eventSink
            if (sink != null) {
                sink(payload)
            } else {
                // detach() entre el post y la ejecución: no se pierde.
                enqueuePending(payload)
            }
        }
    }
}
