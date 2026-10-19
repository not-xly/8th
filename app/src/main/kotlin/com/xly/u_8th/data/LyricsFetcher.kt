package com.xly.u_8th.data

import com.xly.u_8th.domain.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.abs

/**
 * Búsqueda de letras en red: lrclib.net (get/search con datos crudos y
 * limpios) -> NetEase -> lyrics.ovh. Sin estado ni disco: devuelve el texto
 * encontrado (preferiblemente sincronizado) o null.
 */
internal class LyricsFetcher(
    private val http: LyricsHttp,
    /** Lanza todos los intentos a la vez (pantalla abierta) en vez de en cascada. */
    private val parallel: Boolean = false,
) {

    private companion object {
        /** Por debajo de esta longitud normalizada, el título debe ser prefijo. */
        const val SHORT_TITLE_LEN = 5

        /** Candidatos de búsqueda evaluados como máximo. */
        const val SCORED_CANDIDATES = 20
    }

    private suspend fun tryGet(params: Map<String, String>): String? = recoverOrNull {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("lrclib.net")
            .addPathSegments("api/get")
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        val (code, body) = http.get("tryGet", url.toString()) ?: return@recoverOrNull null
        if (code == 200) {
            val json = jsonOrNull(body)
            if (json != null) {
                val synced = json.optStringOrNull("syncedLyrics")
                if (synced != null && synced.trim().isNotEmpty()) return@recoverOrNull synced
                val plain = json.optStringOrNull("plainLyrics")
                if (plain != null && plain.trim().isNotEmpty()) return@recoverOrNull plain
            }
        }
        null
    }

    private suspend fun trySearchParams(artistName: String, trackName: String, durationSec: Int?): String? = recoverOrNull {
        val q = (if (artistName.isNotEmpty()) "$artistName $trackName" else trackName).trim()
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("lrclib.net")
            .addPathSegments("api/search")
            .addQueryParameter("q", q)
            .apply {
                if (artistName.isNotEmpty()) addQueryParameter("artist_name", artistName)
                if (trackName.isNotEmpty()) addQueryParameter("track_name", trackName)
            }
            .build()
        val (code, body) = http.get("trySearch", url.toString()) ?: return@recoverOrNull null
        if (code != 200) return@recoverOrNull null
        val decoded = arrayOrNull(body) ?: return@recoverOrNull null
        // Solo se recorren los primeros SCORED_CANDIDATES: la búsqueda de
        // lrclib devuelve cientos de filas y materializarlas todas era
        // trabajo y memoria tirados.
        val items = (0 until decoded.length())
            .take(SCORED_CANDIDATES)
            .mapNotNull { decoded.optJSONObject(it) }
        if (items.isEmpty()) return@recoverOrNull null
        fun durOk(item: JSONObject): Boolean {
            val d = if (item.has("duration") && !item.isNull("duration")) item.optLong("duration").toInt() else null
            return durationSec == null || d == null || abs(d - durationSec) <= 3
        }
        // Title-only mode demands an approximate duration to avoid another song.
        val candidates =
            if (artistName.isEmpty() && durationSec != null) items.filter { durOk(it) } else items
        for (item in candidates) {
            val synced = item.optStringOrNull("syncedLyrics")
            if (synced != null && synced.trim().isNotEmpty()) return@recoverOrNull synced
        }
        for (item in candidates) {
            val plain = item.optStringOrNull("plainLyrics")
            if (plain != null && plain.trim().isNotEmpty()) return@recoverOrNull plain
        }
        null
    }

    /**
     * Full cascade:
     * 1) lrclib /api/get with raw artist/track (+album/duration)
     * 2) lrclib /api/get with cleaned artist/track
     * 3) lrclib /api/search with raw data
     * 4) lrclib /api/search with cleaned data; 5) title-only search
     * 6) NetEase; 7) lyrics.ovh.
     */
    suspend fun fetch(song: Song): String? {
        // Todo el parseo JSON y el trabajo de regex ocurre aquí dentro, y los
        // llamantes vienen del dispatcher Main (viewModelScope / LaunchedEffect).
        // `readTimeout` solo cubre la espera de red: org.json sobre respuestas
        // de 100-200 KB más los bucles de limpieza bloqueaban el hilo principal
        // cientos de veces durante una sincronización masiva.
        return withContext(Dispatchers.IO) { fetchSyncedLyricsInternal(song) }
    }

    private suspend fun fetchSyncedLyricsInternal(song: Song): String? {
        val artist = (song.artist ?: "").trim()
        val title = song.displayTitle.trim()
        if (title.isEmpty()) return null
        val album = (song.album ?: "").trim()
        val durationSec: Int? = song.durationMs?.let { Math.round(it / 1000.0).toInt() }

        val normTitle = LrcLogic.cleanTitle(title)
        val normArtist = if (artist.isEmpty()) "" else LrcLogic.cleanArtist(artist)
        val cleaned = normTitle != title || (normArtist.isNotEmpty() && normArtist != artist)

        // Orden = prioridad: la primera letra sincronizada gana.
        val attempts = buildList<suspend () -> String?> {
            // 1) Exact attempt only with an artist (lrclib /get requires both).
            if (artist.isNotEmpty()) {
                val getParams = buildMap {
                    put("artist_name", artist)
                    put("track_name", title)
                    if (album.isNotEmpty()) put("album_name", album)
                    if (durationSec != null) put("duration", durationSec.toString())
                }
                add { tryGet(getParams) }
                add { tryGet(mapOf("artist_name" to artist, "track_name" to title)) }
            }
            // 2) Exact attempt with cleaned title/artist (no remaster, feat, etc.).
            if (normArtist.isNotEmpty() && (normTitle != title || normArtist != artist)) {
                add { tryGet(mapOf("artist_name" to normArtist, "track_name" to normTitle)) }
            }
            // 3) Search with raw data.
            add { trySearchParams(artist, title, durationSec) }
            // 4) Search with cleaned data; 5) title-only (for mislabeled artists).
            if (cleaned) {
                add { trySearchParams(normArtist, normTitle, durationSec) }
                add { trySearchParams("", normTitle, durationSec) }
            }
            // 6) NetEase (synced LRC, good international coverage).
            add { fetchFromNetease(normArtist, normTitle, durationSec) }
        }
        // 7) Without a synced version: the first plain lyrics found, else
        //    lyrics.ovh (plain).
        return firstSynced(attempts) ?: fetchFromLyricsOvh(song)
    }

    /**
     * Primera letra sincronizada de [attempts] por orden de prioridad; si no
     * hay ninguna, la primera sin sincronizar (una letra plana no corta la
     * búsqueda: antes la primera plainLyrics de lrclib ganaba y NetEase
     * nunca se consultaba). En modo [parallel] se lanzan todos a la vez y
     * se cancelan los restantes en cuanto el resultado está decidido.
     */
    private suspend fun firstSynced(attempts: List<suspend () -> String?>): String? {
        var plainFallback: String? = null
        fun synced(result: String?): String? {
            if (result == null) return null
            if (LrcLogic.hasTimestamps(result)) return result
            if (plainFallback == null) plainFallback = result
            return null
        }
        if (!parallel) {
            for (attempt in attempts) synced(attempt())?.let { return it }
            return plainFallback
        }
        return coroutineScope {
            val pending = attempts.map { attempt -> async { recoverOrNull { attempt() } } }
            for (deferred in pending) {
                synced(deferred.await())?.let {
                    coroutineContext.cancelChildren()
                    return@coroutineScope it
                }
            }
            plainFallback
        }
    }

    private suspend fun fetchFromNetease(artist: String, title: String, durationSec: Int?): String? {
        if (title.isEmpty()) return null
        // Un título que se normaliza a "" (solo puntuación, o en alfabeto
        // no contemplado: cirílico, árabe, tailandés…) haría que
        // `fuzzyContains` devolviera true para TODOS los candidatos: se
        // guardaría la letra de otra canción.
        if (LrcLogic.normForMatch(title).isEmpty()) return null
        val userAgent = "Mozilla/5.0 (X11; Linux x86_64) 8th/1.0"
        return try {
            val q = listOf(artist, title).filter { it.isNotEmpty() }.joinToString(" ")
            val searchRes = http.request("netease search") {
                val form = FormBody.Builder()
                    .add("s", q)
                    .add("type", "1")
                    .add("limit", "10")
                    .add("offset", "0")
                    .build()
                http.client.newCall(
                    Request.Builder()
                        .url("https://music.163.com/api/search/get")
                        .post(form)
                        .header("User-Agent", userAgent)
                        .header("Referer", "https://music.163.com")
                        .build()
                )
            } ?: return null
            if (searchRes.first != 200) return null
            val root = jsonOrNull(searchRes.second) ?: return null
            val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return null
            if (songs.length() == 0) return null

            var best: JSONObject? = null
            var bestScore = -1
            val count = songs.length()
            for (i in 0 until count) {
                currentCoroutineContext().ensureActive()
                val s = songs.optJSONObject(i) ?: continue
                val name = s.optStringOrNull("name") ?: ""
                val artistsArr = s.optJSONArray("artists")
                val artists = buildList {
                    if (artistsArr != null) {
                        for (j in 0 until artistsArr.length()) {
                            add((artistsArr.optJSONObject(j) ?: continue).optStringOrNull("name") ?: "")
                        }
                    }
                }.joinToString(", ")
                if (!LrcLogic.fuzzyContains(name, title)) continue
                if (artist.isNotEmpty() && !LrcLogic.fuzzyContains(artists, artist)) continue
                // Con títulos cortos el "contiene" es demasiado laxo ("One"
                // casaba con "Someone Like You"): se exige que empiece igual.
                val normName = LrcLogic.normForMatch(name)
                val normTitle = LrcLogic.normForMatch(title)
                if (normTitle.length < SHORT_TITLE_LEN && !normName.startsWith(normTitle)) continue
                var score = if (normName == normTitle) 3 else 0
                val dMs = if (s.has("duration") && !s.isNull("duration")) s.optLong("duration") else null
                if (durationSec != null && dMs != null) {
                    val diff = abs(dMs / 1000.0 - durationSec)
                    if (diff <= 3) {
                        score += 2
                    } else if (diff <= 8) {
                        score += 1
                    }
                }
                if (score > bestScore) {
                    bestScore = score
                    best = s
                }
            }
            val winner = best ?: return null
            val id = winner.opt("id") ?: return null
            // HttpUrl.Builder escaparía el id; interpolarlo a mano lanzaba
            // IllegalArgumentException (silenciosamente atrapada) si la
            // respuesta traía algo que no fuera un entero.
            val lyricUrl = HttpUrl.Builder()
                .scheme("https")
                .host("music.163.com")
                .addPathSegments("api/song/lyric")
                .addQueryParameter("id", id.toString())
                .addQueryParameter("lv", "1")
                .addQueryParameter("kv", "1")
                .addQueryParameter("tv", "-1")
                .build()
            val lyricRes = http.request("netease lyric") {
                http.client.newCall(
                    Request.Builder()
                        .url(lyricUrl)
                        .header("User-Agent", userAgent)
                        .header("Referer", "https://music.163.com")
                        .build()
                )
            } ?: return null
            if (lyricRes.first != 200) return null
            val lyric = jsonOrNull(lyricRes.second)
                ?.optJSONObject("lrc")
                ?.let { it.optStringOrNull("lyric") }
            val trimmed = lyric?.trim()
            // "纯音乐" = instrumental track, not lyrics.
            if (!trimmed.isNullOrEmpty() && !trimmed.contains("纯音乐")) trimmed else null
        } catch (e: CancellationException) {
            // CancellationException es un IllegalStateException: sin este
            // bloque el "cancelar" del usuario se convertía en un null y la
            // cascada seguía encadenando peticiones de red.
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchFromLyricsOvh(song: Song): String? {
        val rawArtist = (song.artist ?: "").trim()
        val title = song.displayTitle.trim()
        if (rawArtist.isEmpty() || title.isEmpty()) return null
        val artist = LrcLogic.cleanArtist(rawArtist)
        if (artist.isEmpty()) return null
        return try {
            val url = HttpUrl.Builder()
                .scheme("https")
                .host("api.lyrics.ovh")
                .addPathSegment("v1")
                .addPathSegment(artist)
                .addPathSegment(title)
                .build()
            val res = http.request("ovh") {
                http.client.newCall(
                    Request.Builder().url(url).header("User-Agent", "8th/1.0").build()
                )
            } ?: return null
            if (res.first == 200) {
                val lyrics = jsonOrNull(res.second)
                    ?.let { it.optStringOrNull("lyrics") }
                    ?.trim()
                if (!lyrics.isNullOrEmpty()) lyrics else null
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
