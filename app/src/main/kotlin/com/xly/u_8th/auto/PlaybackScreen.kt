package com.xly.u_8th.auto

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class PlaybackScreen(carContext: CarContext) : Screen(carContext) {

    private val mainHandler = Handler(Looper.getMainLooper())

    // Último snapshot + instante de recepción: la posición mostrada avanza
    // en local (positionMs + tiempo transcurrido si playing) sin necesidad
    // de que Dart reenvíe nada.
    private var snapshot = AutoPlaybackState.current()
    private var snapshotAt = SystemClock.elapsedRealtime()

    // Tick SOLO mientras suena: antes invalidaba cada 1s para siempre,
    // incluso en pausa (rebuild completo del ItemList 60x/min + riesgo de
    // que el host limitase las invalidaciones).
    private val tickRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!snapshot.playing) return
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                invalidate()
            }
            mainHandler.postDelayed(this, 1000L)
        }
    }

    // Push real: Dart envía snapshot -> invalidar. Antes este sink existía
    // pero nadie lo conectaba (attach/detach eran código muerto) y la
    // pantalla vivía de polling ciego.
    private val stateListener: (AutoPlaybackSnapshot) -> Unit = { s ->
        snapshot = s
        snapshotAt = SystemClock.elapsedRealtime()
        mainHandler.post {
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                invalidate()
            }
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                snapshot = AutoPlaybackState.current()
                snapshotAt = SystemClock.elapsedRealtime()
                AutoPlaybackState.attach(stateListener)
                if (snapshot.playing) {
                    mainHandler.removeCallbacks(tickRunnable)
                    mainHandler.postDelayed(tickRunnable, 1000L)
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                AutoPlaybackState.detach()
                mainHandler.removeCallbacks(tickRunnable)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                AutoPlaybackState.detach()
                mainHandler.removeCallbacks(tickRunnable)
            }
        })
    }

    override fun onGetTemplate(): Template {
        // Sin re-armar el tick aquí: onStart lo gestiona según haya sonido.
        return buildTemplate()
    }

    /** Posición mostrada: extrapola localmente entre snapshots. */
    private fun displayedPositionMs(): Long {
        val base = snapshot.positionMs
        if (!snapshot.playing) return base
        val elapsed = SystemClock.elapsedRealtime() - snapshotAt
        return if (snapshot.durationMs > 0) {
            (base + elapsed).coerceAtMost(snapshot.durationMs)
        } else {
            base + elapsed
        }
    }

    private fun buildTemplate(): Template {
        val state = snapshot

        if (state.title.isEmpty()) {
            val empty = ItemList.Builder()
                .setNoItemsMessage("No hay canción activa. Selecciona una canción desde la biblioteca.")
                .build()
            return ListTemplate.Builder()
                .setSingleList(empty)
                .setTitle("Reproduciendo")
                .setHeaderAction(Action.BACK)
                .build()
        }

        val playPauseIconRes =
            if (state.playing) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play

        // Header admite 1 start + máx 2 end (3 petaba en algunos hosts):
        // play/pause + next en header, prev como fila.
        val header = Header.Builder()
            .setTitle(state.title)
            .setStartHeaderAction(Action.BACK)
            .addEndHeaderAction(
                Action.Builder()
                    .setIcon(
                        CarIcon.Builder(
                            IconCompat.createWithResource(carContext, playPauseIconRes)
                        ).build()
                    )
                    .setOnClickListener {
                        if (state.playing) AutoBridge.sendControl(carContext, "pause")
                        else AutoBridge.sendControl(carContext, "play")
                    }
                    .build()
            )
            .addEndHeaderAction(
                Action.Builder()
                    .setIcon(
                        CarIcon.Builder(
                            IconCompat.createWithResource(carContext, android.R.drawable.ic_media_next)
                        ).build()
                    )
                    .setOnClickListener {
                        AutoBridge.sendControl(carContext, "next")
                    }
                    .build()
            )
            .build()

        val builder = ItemList.Builder()
        val artistAlbum = listOfNotNull(
            state.artist.ifEmpty { null },
            state.album.ifEmpty { null }
        ).joinToString(" • ")
        val progress = formatDuration(displayedPositionMs()) + " / " + formatDuration(state.durationMs)

        builder.addItem(
            Row.Builder()
                .setTitle(state.title)
                .addText(artistAlbum.ifEmpty { "Desconocido" })
                .build()
        )
        builder.addItem(
            Row.Builder()
                .setTitle("Progreso")
                .addText(progress)
                .build()
        )
        builder.addItem(
            Row.Builder()
                .setTitle("Anterior")
                .addText("Volver a la pista anterior")
                .setOnClickListener {
                    AutoBridge.sendControl(carContext, "prev")
                }
                .build()
        )
        if (state.queue.isNotEmpty()) {
            builder.addItem(
                Row.Builder()
                    .setTitle("Cola")
                    .addText("${state.queue.size} canciones • en pista ${state.queueIndex + 1}")
                    .setOnClickListener {
                        screenManager.push(QueueScreen(carContext))
                    }
                    .build()
            )
        }

        val repeatLabel = when (state.loopMode) {
            2 -> "Repetir: una"
            1 -> "Repetir: todo"
            else -> "Repetir: desactivado"
        }
        val shuffleLabel = if (state.shuffled) "Aleatorio: activado" else "Aleatorio: desactivado"

        builder.addItem(
            Row.Builder()
                .setTitle(repeatLabel)
                .setOnClickListener {
                    AutoBridge.sendControl(carContext, "cycleLoop")
                }
                .build()
        )
        builder.addItem(
            Row.Builder()
                .setTitle(shuffleLabel)
                .setOnClickListener {
                    AutoBridge.sendControl(carContext, "toggleShuffle")
                }
                .build()
        )

        return ListTemplate.Builder()
            .setSingleList(builder.build())
            .setHeader(header)
            .build()
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
