package com.xly.u_8th.util

/**
 * FNV-1a 32-bit hash over the UTF-16 code units of [s].
 *
 * Stable across launches and across platforms (fixed byte order), which makes
 * it valid for file names and persistent cache keys. Must stay bit-identical
 * with the previous implementation so existing cache files and .lrc names
 * keep resolving.
 */
fun fnv1a(s: String): Long {
    var hash = 0x811c9dc5L
    for (i in s.indices) {
        hash = hash xor s[i].code.toLong()
        hash = (hash * 0x01000193L) and 0xFFFFFFFFL
    }
    return hash
}

/**
 * Hex string of [fnv1a] trimmed to [length] digits (collision suffix).
 *
 * @throws IllegalArgumentException if [length] exceeds the 8 hex digits a
 * 32-bit hash can produce (a wider request used to crash with
 * StringIndexOutOfBoundsException).
 */
fun fnv1aHex(s: String, length: Int = 6): String {
    require(length in 0..8) { "fnv1aHex: length out of range 0..8 (was $length)" }
    return fnv1a(s).toString(16).padStart(8, '0').substring(0, length)
}

/**
 * Sampled FNV-1a over bytes: hashes length + up to [samples] spaced bytes
 * (always including the last one) instead of walking megabytes on the UI
 * thread. Bytes are read unsigned, as FNV-1a specifies.
 */
fun fnv1aBytesSampled(bytes: ByteArray, samples: Int = 512): Long {
    require(samples > 0) { "fnv1aBytesSampled: samples must be > 0" }
    var hash = 0x811c9dc5L
    hash = hash xor bytes.size.toLong()
    hash = (hash * 0x01000193L) and 0xFFFFFFFFL
    if (bytes.isEmpty()) return hash
    val step = maxOf(1, minOf(4096, (bytes.size + samples - 1) / samples))
    var i = 0
    while (i < bytes.size) {
        hash = hash xor (bytes[i].toInt() and 0xFF).toLong()
        hash = (hash * 0x01000193L) and 0xFFFFFFFFL
        i += step
    }
    // The stride alone could skip the tail: two covers differing only in
    // their last bytes would hash identically.
    val last = bytes.size - 1
    if (last % step != 0) {
        hash = hash xor (bytes[last].toInt() and 0xFF).toLong()
        hash = (hash * 0x01000193L) and 0xFFFFFFFFL
    }
    return hash
}
