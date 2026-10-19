package com.xly.u_8th.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.Template

class AllSongsScreen(carContext: CarContext, private val offset: Int = 0) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val tracks = LibraryProvider.tracks()
        val max = PAGE_ROWS
        val from = offset.coerceIn(0, tracks.size)
        val to = (from + max).coerceAtMost(tracks.size)
        val visible = tracks.subList(from, to)
        // Si la biblioteca entera cabe, se envía completa (la cola sigue
        // más allá de lo visible); si no, la página actual.
        val queueSource = if (tracks.size <= max) tracks else visible
        val builder = ItemList.Builder()

        for ((idx, track) in visible.withIndex()) {
            val tapIndex = if (queueSource === visible) idx else from + idx
            builder.addItem(
                Row.Builder()
                    .setTitle(track.title)
                    .addText(track.artist ?: "Desconocido")
                    .addText(track.album ?: "")
                    .setOnClickListener {
                        AutoBridge.requestPlayQueue(carContext, queueSource, tapIndex)
                    }
                    .build()
            )
        }

        // Paginación: sin esto el tema 101+ era inalcanzable desde el coche
        // (el título "(100/500)" avisaba del recorte pero no ofrecía remedio).
        if (to < tracks.size) {
            val remaining = tracks.size - to
            val totalPages = (tracks.size + max - 1) / max
            builder.addItem(
                Row.Builder()
                    .setTitle("Siguientes ${remaining.coerceAtMost(max)} canciones")
                    .addText("Página ${to / max + 1} de $totalPages")
                    .setOnClickListener {
                        screenManager.push(AllSongsScreen(carContext, to))
                    }
                    .build()
            )
        }

        if (visible.isEmpty()) {
            builder.setNoItemsMessage("No hay canciones. Añade música en el móvil.")
        }

        val title = if (tracks.size > max) {
            "Todas las canciones ${from + 1}-$to/${tracks.size}"
        } else {
            "Todas las canciones (${tracks.size})"
        }
        return listWithHeader(builder.build(), title).build()
    }

    private companion object {
        /**
         * Un pista menos que el límite del host: la fila de paginación
         * también ocupa una. `ListTemplate` **trunca** en silencio a 100
         * ítems (no lanza), así que sin reservar este hueco la fila de
         * "Siguientes" sería siempre la última eliminada y las canciones
         * 101+ quedarían inalcanzables desde el coche.
         */
        const val PAGE_ROWS = AutoBridge.MAX_ITEMS - 1
    }
}
