package com.xly.u_8th.util

/**
 * FNV-1a 32-bit hash over the UTF-16 code units of [s].
 *
 * It is stable across launches (unlike `String.hashCode` in some runtimes'
 * expectations), which makes it valid for file names and persistent cache
 * keys. Must stay bit-identical with the previous implementation so existing
 * cache files and .lrc names keep resolving.
 */
fun fnv1a(s: String): Long {
    var hash = 0x811c9dc5L
    for (i in s.indices) {
        hash = hash xor s[i].code.toLong()
        hash = (hash * 0x01000193L) and 0xFFFFFFFFL
    }
    return hash
}

/** Hex string of [fnv1a] trimmed to [length] digits (collision suffix). */
fun fnv1aHex(s: String, length: Int = 6): String =
    fnv1a(s).toString(16).padStart(8, '0').substring(0, length)
