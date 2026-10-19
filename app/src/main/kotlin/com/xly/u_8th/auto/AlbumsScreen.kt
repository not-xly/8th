package com.xly.u_8th.auto

import androidx.core.net.toUri
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat

class AlbumsScreen(
    carContext: CarContext,
    private val offset: Int = 0,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val albums = LibraryProvider.albums()
        // Una fila menos que el límite del host: la de paginación ocupa hueco
        // y ListTemplate trunca en silencio al superarlo.
        val max = PAGE_ROWS
        val from = offset.coerceIn(0, albums.size)
        val to = (from + max).coerceAtMost(albums.size)
        val visible = albums.subList(from, to)
        val builder = ItemList.Builder()

        for (album in visible) {
            val rowBuilder = Row.Builder()
                .setTitle(album.title)
                .addText("${album.trackCount} pistas • ${album.artist ?: "Desconocido"}")
                .setOnClickListener {
                    screenManager.push(AlbumDetailScreen(carContext, album.key))
                }
            album.loadIcon()?.let { rowBuilder.setImage(it) }
            builder.addItem(rowBuilder.build())
        }

        if (to < albums.size) {
            val totalPages = (albums.size + max - 1) / max
            builder.addItem(
                Row.Builder()
                    .setTitle("Siguientes ${(albums.size - to).coerceAtMost(max)} álbumes")
                    .addText("Página ${to / max + 1} de $totalPages")
                    .setOnClickListener {
                        screenManager.push(AlbumsScreen(carContext, to))
                    }
                    .build()
            )
        }

        if (visible.isEmpty()) {
            builder.setNoItemsMessage("No hay álbumes. Añade música en el móvil.")
        }

        val title = if (albums.size > to) {
            "Álbumes ${from + 1}-$to/${albums.size}"
        } else {
            "Álbumes (${albums.size})"
        }
        return listWithHeader(builder.build(), title).build()
    }

    private companion object {
        const val PAGE_ROWS = AutoBridge.MAX_ITEMS - 1
    }
}

/** Carátula vía content URI: el HOST de plantillas la descarga, escala y
 *  cachea de forma asíncrona (vía recomendada para
 *  content://media/.../albumart). Antes decodificábamos hasta 100 bitmaps
 *  en el hilo de templates en el primer render = ANR, y el LruCache de
 *  bitmaps retenía ~10 MB estáticos en un proceso que debe ser ligero. */
fun Album.loadIcon(): CarIcon? {
    if (artUri.isNullOrEmpty()) return null
    return try {
        CarIcon.Builder(IconCompat.createWithContentUri(artUri.toUri())).build()
    } catch (_: Exception) {
        null
    }
}
