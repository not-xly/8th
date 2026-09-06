package com.xly.u_8th.domain

/** Repeat modes, matching the wire values used by the car screens. */
enum class LoopMode(val wire: Int) {
    OFF(0),
    ALL(1),
    ONE(2);

    companion object {
        fun fromWire(value: Int): LoopMode = entries.firstOrNull { it.wire == value } ?: ALL
    }
}

/** Main screen tabs. */
enum class ViewTab { ALBUMS, QUEUE }
