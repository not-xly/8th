package com.xly.u_8th.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template

class QueueScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val state = AutoPlaybackState.current()
        val queue = state.queue

        if (queue.isEmpty()) {
            val empty = ItemList.Builder()
                .setNoItemsMessage("Cola vacía")
                .build()
            return ListTemplate.Builder()
                .setSingleList(empty)
                .setTitle("Cola")
                .setHeaderAction(Action.BACK)
                .build()
        }

        // Ventana alrededor del tema actual: la cola puede tener 1000+
        // temas y el host limita a ~100 ítems.
        val max = AutoBridge.MAX_ITEMS
        val current = state.queueIndex.coerceIn(0, queue.size - 1)
        val start = (current - max / 2).coerceAtLeast(0).let {
            if (it + max > queue.size) (queue.size - max).coerceAtLeast(0) else it
        }
        val end = (start + max).coerceAtMost(queue.size)
        val window = queue.subList(start, end)

        val builder = ItemList.Builder()
        for ((offset, item) in window.withIndex()) {
            val idx = start + offset
            val isPlaying = idx == state.queueIndex
            // Sin glifo ▶: puede no renderizar en head-units.
            val title = if (isPlaying) "${item.title} (sonando)" else item.title
            builder.addItem(
                Row.Builder()
                    .setTitle(title)
                    .addText(item.artist ?: "Desconocido")
                    .addText(formatDuration(item.durationMs))
                    .setOnClickListener {
                        AutoBridge.sendQueueAction(carContext, "playIndex", idx)
                    }
                    .build()
            )
        }

        val title = if (queue.size > window.size) {
            "Cola (${start + 1}-${end}/${queue.size})"
        } else {
            "Cola (${queue.size})"
        }
        return ListTemplate.Builder()
            .setSingleList(builder.build())
            .setTitle(title)
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
