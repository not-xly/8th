package com.xly.u_8th.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * HTTP de las fuentes de letras: peticiones con reintento (error de red,
 * timeout, 429 o 5xx; esperas de 600 ms -> 1,2 s -> 2,4 s) y cuerpo
 * acotado a [MAX_BODY_BYTES].
 */
internal class LyricsHttp(
    val client: OkHttpClient = DefaultClient,
    private val retryDelaysMs: LongArray = BULK_RETRIES_MS,
) {

    companion object {
        /**
         * Un único cliente para toda la app: cada instancia crea su propio
         * `Dispatcher` (hilo + cola) y su propio `ConnectionPool`, que
         * nunca se cierran. Además `callTimeout` es necesario porque
         * `readTimeout` es **por lectura**: un servidor que aceptase la
         * conexión y enviase el cuerpo a trozos lentos mantendría la
         * petición viva indefinidamente.
         */
        val DefaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()

        /** Sincronización masiva: paciencia con los 429/503 de lrclib. */
        val BULK_RETRIES_MS = longArrayOf(600, 1200, 2400)

        /**
         * Pantalla de letras abierta: un solo reintento corto. lrclib
         * responde 503 "busy" a menudo en /get de pistas desconocidas y la
         * espera de 4,2 s por petición hacía que una letra tardase decenas
         * de segundos en aparecer.
         */
        val INTERACTIVE_RETRIES_MS = longArrayOf(800)

        /** Techo del cuerpo leido de un endpoint (2 MB de sobra). */
        const val MAX_BODY_BYTES = 2L * 1024 * 1024
    }

    private suspend fun awaitBody(call: Call): Pair<Int, String?> = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    // peekBody(limit) evita cargar en memoria una respuesta
                    // enorme: `string()` sin tope almacenaba el cuerpo entero.
                    // Un byte más que el tope para distinguir "cabe justo" de
                    // "truncado": un cuerpo cortado daría JSON roto, así que
                    // se trata como respuesta vacía de forma explícita.
                    val result = response.use {
                        val body = it.peekBody(MAX_BODY_BYTES + 1)
                        it.code to (if (body.contentLength() > MAX_BODY_BYTES) null else body.string())
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    suspend fun request(label: String, request: () -> Call): Pair<Int, String?>? {
        var attempt = 0
        while (true) {
            try {
                currentCoroutineContext().ensureActive()
                val res = awaitBody(request())
                if ((res.first == 429 || res.first >= 500) && attempt < retryDelaysMs.size) {
                    lrcLog("$label ${res.first}, retry ${attempt + 1}")
                    delay(retryDelaysMs[attempt])
                    attempt++
                    continue
                }
                return res
            } catch (e: IOException) {
                if (attempt >= retryDelaysMs.size) {
                    lrcLog("$label definitive error: $e")
                    return null
                }
                lrcLog("$label error ($e), retry ${attempt + 1}")
                delay(retryDelaysMs[attempt])
                attempt++
            }
        }
    }

    /**
     * GET con User-Agent propio; null si se agotan los reintentos.
     * [headers] se añaden a la petición (p. ej. el Referer de NetEase).
     */
    suspend fun get(label: String, url: String, headers: Map<String, String> = DEFAULT_HEADERS): Pair<Int, String?>? =
        request(label) {
            client.newCall(
                Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build(),
            )
        }
}

private val DEFAULT_HEADERS = mapOf("User-Agent" to "8th/1.0")

internal fun lrcLog(message: String) {
    runCatching { Log.d("LRC", message) }
}

/** Ejecuta [block]; cualquier fallo salvo la cancelación se convierte en null. */
internal suspend fun <T> recoverOrNull(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

internal fun jsonOrNull(body: String?): JSONObject? {
    if (body == null) return null
    return try { JSONObject(body) } catch (_: Exception) { null }
}

internal fun arrayOrNull(body: String?): JSONArray? {
    if (body == null) return null
    return try { JSONArray(body) } catch (_: Exception) { null }
}
