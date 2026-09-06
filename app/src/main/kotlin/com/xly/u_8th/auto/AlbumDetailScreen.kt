package com.xly.u_8th.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template

class AlbumDetailScreen(
    carContext: CarContext,
    private val albumId: Long,
    private val offset: Int = 0
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val tracks = LibraryProvider.tracksForAlbum(albumId)
        val max = AutoBridge.MAX_ITEMS
        val from = offset.coerceIn(0, tracks.size)
        val to = (from + max).coerceAtMost(tracks.size)
        val visible = tracks.subList(from, to)
        val queueSource = if (tracks.size <= max) tracks else visible
        val builder = ItemList.Builder()

        for ((idx, track) in visible.withIndex()) {
            val tapIndex = if (queueSource === visible) idx else from + idx
            builder.addItem(
                Row.Builder()
                    .setTitle(track.title)
                    .addText(track.artist ?: "Desconocido")
                    .addText(formatDuration(track.durationMs))
                    .setOnClickListener {
                        AutoBridge.requestPlayQueue(carContext, queueSource, tapIndex)
                    }
                    .build()
            )
        }

        if (to < tracks.size) {
            builder.addItem(
                Row.Builder()
                    .setTitle("Siguientes ${(tracks.size - to).coerceAtMost(max)} canciones")
                    .addText("Más pistas del álbum")
                    .setOnClickListener {
                        screenManager.push(AlbumDetailScreen(carContext, albumId, to))
                    }
                    .build()
            )
        }

        if (visible.isEmpty()) {
            builder.setNoItemsMessage("Álbum vacío")
        }

        return ListTemplate.Builder()
            .setSingleList(builder.build())
            .setTitle(tracks.firstOrNull()?.album ?: "Álbum")
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return ""
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }
}
