package com.xly.u_8th

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.xly.u_8th.ui.EighthApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Conecta ya con la sesión: el controlador se creaba con el primer
        // comando, así que el servicio no arrancaba al abrir la app y la
        // última cola guardada nunca se restauraba (y ese primer comando
        // cancelaba la restauración). Conectar no reproduce nada.
        EighthApplication.get(this).playerConnection.launch { awaitController() }
        setContent {
            EighthApp()
        }
    }
}
