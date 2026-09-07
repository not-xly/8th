package com.xly.u_8th.auto

import androidx.core.net.toUri
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat

class AlbumsScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val albums = LibraryProvider.albums()
        val visible = albums.take(AutoBridge.MAX_ITEMS)
        val builder = ItemList.Builder()

        for (album in visible) {
            val rowBuilder = Row.Builder()
                .setTitle(album.title)
                .addText("${album.trackCount} pistas • ${album.artist ?: "Desconocido"}")
                .setOnClickListener {
                    screenManager.push(AlbumDetailScreen(carContext, album.id))
                }
            album.loadIcon()?.let { rowBuilder.setImage(it) }
            builder.addItem(rowBuilder.build())
        }

        if (visible.isEmpty()) {
            builder.setNoItemsMessage("No hay álbumes. Añade música en el móvil.")
        }

        val title = if (albums.size > visible.size) {
            "Álbumes (${visible.size}/${albums.size})"
        } else {
            "Álbumes (${albums.size})"
        }
        return ListTemplate.Builder()
            .setSingleList(builder.build())
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .build()
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
