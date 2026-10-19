package com.xly.u_8th.auto

import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ListTemplate

/**
 * Título + acción de cabecera de las pantallas del coche.
 *
 * `ListTemplate.Builder.setTitle` / `setHeaderAction` están obsoletos desde
 * la librería: la vía vigente es un `Header`. Centralizarlo evita repetir
 * el patrón (y las deprecaciones) en cada pantalla.
 */
fun appHeader(title: String, startAction: Action = Action.BACK): Header =
    Header.Builder()
        .setTitle(title)
        .setStartHeaderAction(startAction)
        .build()

/**
 * Plantilla de lista con cabecera; única forma no obsoleta de dar título y
 * acción de vuelta en car-app 1.7.
 */
fun listWithHeader(singleList: androidx.car.app.model.ItemList, title: String): ListTemplate.Builder =
    ListTemplate.Builder()
        .setSingleList(singleList)
        .setHeader(appHeader(title))