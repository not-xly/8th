package com.xly.u_8th.data

/** One lyric line: [timestampMs] is the line start time (0 for unsynced text). */
data class LrcLine(val timestampMs: Long, val text: String)
