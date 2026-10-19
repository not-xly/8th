package com.xly.u_8th.util

/**
 * Formats milliseconds as `m:ss`, or `h:mm:ss` when hours > 0.
 *
 * Non-positive values collapse to `0:00`: with a negative input the integer
 * divisions produced labels like `0:-5`.
 */
fun fmtMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "$m:${s.toString().padStart(2, '0')}"
    }
}
