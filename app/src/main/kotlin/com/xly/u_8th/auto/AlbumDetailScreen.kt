package com.xly.u_8th.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.xly.u_8th.util.fmtMs

/**
 * @param albumKey clave de [LibraryProvider.Album]: "id:<albumId>" o
 * "unk:<título>" para los álbumes sin id en MediaStore. Con solo el id,
 * todos los "Desconocido" (id = 0) compartían la misma pantalla.
 */
class AlbumDetailScreen(
    carContext: CarContext,
    private val albumKey: String,
    private val offset: Int = 0
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val tracks = LibraryProvider.tracksForAlbum(albumKey)
        val max = PAGE_ROWS
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
                    .addText(if (track.durationMs > 0) fmtMs(track.durationMs) else "")
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
                        screenManager.push(AlbumDetailScreen(carContext, albumKey, to))
                    }
                    .build()
            )
        }

        if (visible.isEmpty()) {
            builder.setNoItemsMessage("Álbum vacío")
        }

        return listWithHeader(builder.build(), tracks.firstOrNull()?.album ?: "Álbum").build()
    }

    private companion object {
        /** Una fila menos que el límite del host: la de paginación ocupa hueco. */
        const val PAGE_ROWS = AutoBridge.MAX_ITEMS - 1
    }
}
