package com.xly.u_8th.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat

class LibraryScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        // Primer arranque en el coche: loading inmediato y scan en
        // background. onGetTemplate debe volver rápido; escanear MediaStore
        // aquí (como antes) bloqueaba el main thread del servicio = ANR.
        if (LibraryProvider.needsRescan()) {
            LibraryProvider.refreshAsync(carContext) { invalidate() }
            // Loading solo mientras no se ha escaneado nunca: si el fallo fue
            // por permiso ausente hay que mostrar el aviso accionable, no un
            // spinner que nunca termina.
            if (!LibraryProvider.refreshedOnce) {
                return ListTemplate.Builder()
                    .setLoading(true)
                    .setHeader(appHeader("8th", Action.APP_ICON))
                    .build()
            }
        }

        val builder = ItemList.Builder()

        val state = AutoPlaybackState.current()
        if (state.title.isNotEmpty()) {
            builder.addItem(
                Row.Builder()
                    .setTitle("Reproduciendo ahora")
                    .addText("${state.title} • ${state.artist.ifEmpty { "Desconocido" }}")
                    .setImage(
                        CarIcon.Builder(IconCompat.createWithResource(carContext, android.R.drawable.ic_media_play)).build()
                    )
                    .setOnClickListener {
                        screenManager.push(PlaybackScreen(carContext))
                    }
                    .build()
            )
        }

        val trackCount = LibraryProvider.tracks().size
        val albumCount = LibraryProvider.albums().size
        if (trackCount == 0) {
            builder.addItem(
                Row.Builder()
                    .setTitle("Sin acceso a la biblioteca")
                    .addText("Concede permiso de audio en el móvil")
                    .build()
            )
        }

        builder.addItem(
            Row.Builder()
                .setTitle("Todas las canciones")
                .addText("$trackCount pistas")
                .setImage(
                    CarIcon.Builder(IconCompat.createWithResource(carContext, android.R.drawable.ic_media_play)).build()
                )
                .setOnClickListener {
                    screenManager.push(AllSongsScreen(carContext))
                }
                .build()
        )

        builder.addItem(
            Row.Builder()
                .setTitle("Álbumes")
                .addText("$albumCount álbumes")
                .setImage(
                    CarIcon.Builder(IconCompat.createWithResource(carContext, android.R.drawable.ic_menu_gallery)).build()
                )
                .setOnClickListener {
                    screenManager.push(AlbumsScreen(carContext))
                }
                .build()
        )

        builder.addItem(
            Row.Builder()
                .setTitle("Aleatorio total")
                .addText("Reproducir toda la biblioteca en orden aleatorio")
                .setImage(
                    CarIcon.Builder(IconCompat.createWithResource(carContext, android.R.drawable.ic_menu_sort_by_size)).build()
                )
                .setOnClickListener {
                    // Sin lista gigante por la sesión: el servicio baraja la
                    // biblioteca (evita TransactionTooLarge con 5000 temas).
                    AutoBridge.sendControl(carContext, "shuffleAll")
                }
                .build()
        )

        return ListTemplate.Builder()
            .setSingleList(builder.build())
            .setHeader(appHeader("8th", Action.APP_ICON))
            .build()
    }
}
