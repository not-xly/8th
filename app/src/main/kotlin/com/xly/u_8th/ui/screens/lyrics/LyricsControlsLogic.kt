package com.xly.u_8th.ui.screens.lyrics

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal const val MAX_LRC_IMPORT_BYTES = 256 * 1024

internal fun readLrcImport(input: InputStream): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer, 0, minOf(buffer.size, MAX_LRC_IMPORT_BYTES + 1 - output.size()))
        // read() solo devuelve 0 si se pide 0 bytes, y eso no ocurre: el
        // límite+1 garantiza hueco. Tratarlo como fin evita un bucle infinito
        // con streams defectuosos.
        if (count <= 0) break
        output.write(buffer, 0, count)
        require(output.size() <= MAX_LRC_IMPORT_BYTES) { "La letra supera el límite de 256 KiB" }
    }
    val text = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(output.toByteArray())).toString()
    return validateLrcImport(text)
}

internal fun validateLrcImport(text: String): String {
    require(text.toByteArray(Charsets.UTF_8).size <= MAX_LRC_IMPORT_BYTES) { "La letra supera el límite de 256 KiB" }
    val normalized = text.removePrefix("\uFEFF").trim()
    require(normalized.isNotEmpty()) { "El archivo está vacío" }
    require('\u0000' !in normalized) { "El archivo debe ser texto UTF-8" }
    require(Regex("\\[\\d{1,3}:[0-5]\\d(?:[.:]\\d{1,3})?]\\s*\\S").containsMatchIn(normalized)) {
        "No hay líneas .lrc válidas; usa [mm:ss.xxx] Texto"
    }
    return normalized
}

internal fun lyricsResumeDelay(nowMs: Long, suppressUntilMs: Long): Long =
    (suppressUntilMs - nowMs).coerceAtLeast(0L)
