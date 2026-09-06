package com.xly.u_8th.auto

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class EighthSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        val screen = LibraryScreen(carContext)
        // Scan en background + invalidate al terminar: onCreateScreen corre
        // en el main thread del servicio y un scan completo de MediaStore
        // ahí congelaba el coche al conectar (ANR del host de plantillas).
        LibraryProvider.refreshAsync(carContext) { screen.invalidate() }
        return screen
    }
}
