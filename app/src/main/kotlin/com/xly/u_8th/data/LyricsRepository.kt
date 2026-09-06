package com.xly.u_8th.data

import android.content.Context
import android.util.Log
import com.xly.u_8th.domain.Song
import com.xly.u_8th.util.fnv1aHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Pure (context-free) lyrics logic, split from [LyricsRepository] so the JVM
 * test suite can exercise naming, cleaning and LRC parsing without Android.
 */
object LrcLogic {

    // ---- File name scheme (must stay identical to the Flutter build so
    // existing .lrc files keep matching) ----

    /** Removes filename-illegal characters, collapses whitespace, caps at 80 chars. */
    fun sanitize(s: String): String {
        var r = s.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
        r = r.replace(Regex("""\s+"""), " ").trim()
        if (r.isEmpty()) return "unknown"
        if (r.length > 80) r = r.substring(0, 80)
        return r
    }

    /** "a/b/c.ext" -> "c" (no extension, leading-dot files kept whole). */
    private fun baseWithoutExtension(raw: String): String {
        val base = raw.substringAfterLast('/')
        val dot = base.lastIndexOf('.')
        return if (dot <= 0) base else base.substring(0, dot)
    }

    fun baseNameFor(song: Song): String {
        val display = song.displayTitle.trim()
        val artist = (song.artist ?: "").trim()
        val base = when {
            artist.isNotEmpty() && display.isNotEmpty() -> "${sanitize(artist)} - ${sanitize(display)}"
            display.isNotEmpty() -> sanitize(display)
            else -> sanitize(baseWithoutExtension(song.filePath ?: song.path))
        }
        // Short stable hash avoids collisions (same title/artist on different files).
        return "${base}_${fnv1aHex(song.path)}"
    }

    // ---- Title/artist cleaning for search ----

    private val FEAT_IN_TITLE = Regex(
        pattern = """\s*(?:[\(\[\{]\s*)?(?:feat\.?|ft\.?|featuring)\b[^\)\]\}]*[\)\]\}]?\s*$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val NOISE_TAG = Regex(
        pattern = """(remaster|remastered|live|acoustic|instrumental|version|edit|mix|remix|deluxe|bonus|anniversary|mono|stereo|karaoke|demo)""",
        option = RegexOption.IGNORE_CASE,
    )
    private val BRACKET_GROUP = Regex("""[\(\[\{][^\)\]\}]*[\)\]\}]""")
    private val DASH_SUFFIX = Regex(
        pattern = """\s*[-–—]\s*(?:remaster(?:ed)?\b|live\b|\S*\s?(?:version|edit|mix)\b).*$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val WHITESPACE_RUN = Regex("""\s+""")
    private val EDGE_DASH = Regex("""^[-–—·]+\s*|\s*[-–—·]+$""")

    fun squash(s: String): String =
        s.replace(WHITESPACE_RUN, " ").trim().replace(EDGE_DASH, "")

    /** Strips "feat. X" and noise tags in brackets ("(Remastered 2011)", "[Live]", "- Radio Edit"). */
    fun cleanTitle(raw: String): String {
        var t = raw.trim()
        t = FEAT_IN_TITLE.replaceFirst(t, " ")
        var changed = true
        var guard = 0
        while (changed && guard++ < 10) {
            changed = false
            // Reverse order so replacing one group does not shift earlier ranges.
            for (g in BRACKET_GROUP.findAll(t).toList().asReversed()) {
                if (NOISE_TAG.containsMatchIn(g.value)) {
                    t = t.replaceRange(g.range, " ")
                    changed = true
                }
            }
            val t2 = DASH_SUFFIX.replaceFirst(t, " ")
            if (t2 != t) {
                t = t2
                changed = true
            }
        }
        val cleaned = squash(t)
        return cleaned.ifEmpty { raw.trim() }
    }

    /** First primary artist ("A, B & C feat. D" -> "A"); respects "Tyler, The Creator". */
    fun cleanArtist(raw: String): String = Artists.primaryArtist(raw) ?: raw.trim()

    // ---- Fuzzy matching (normalized contains) ----

    fun normForMatch(s: String): String =
        s.lowercase().replace(Regex("""[^a-z0-9\u4e00-\u9fff\u3040-\u30ff\uac00-\ud7af]"""), "")

    fun fuzzyContains(haystack: String, needle: String): Boolean {
        val n = normForMatch(needle)
        if (n.isEmpty()) return true
        val h = normForMatch(haystack)
        return h.isNotEmpty() && h.contains(n)
    }

    // ---- LRC parsing ----

    private val LRC_TIMESTAMP = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]""")
    private val TAG_LINE = Regex("""^\[(ti|ar|al|by|offset):""", RegexOption.IGNORE_CASE)

    /** Supports multiple timestamps per line and [mm:ss.xx]; returns lines sorted by time. */
    fun parseLrc(content: String): List<LrcLine> {
        val lines = mutableListOf<LrcLine>()
        for (rawLine in content.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (TAG_LINE.containsMatchIn(line)) continue
            val matches = LRC_TIMESTAMP.findAll(line).toList()
            if (matches.isEmpty()) continue
            val text = line.replace(LRC_TIMESTAMP, "").trim()
            if (text.isEmpty()) continue
            for (m in matches) {
                val min = m.groupValues[1].toIntOrNull() ?: 0
                val sec = m.groupValues[2].toIntOrNull() ?: 0
                val frac = m.groupValues[3]
                val millis = when (frac.length) {
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    3 -> frac.toLong()
                    else -> 0L
                }
                lines.add(LrcLine(timestampMs = (min * 60 + sec) * 1000L + millis, text = text))
            }
        }
        lines.sortBy { it.timestampMs }
        return lines
    }

    /** Plain (unsynced) lyrics: every non-empty line becomes a line at timestamp 0. */
    fun plainLines(content: String): List<LrcLine> =
        content.split('\n')
            .filter { it.trim().isNotEmpty() }
            .map { LrcLine(0, it.trim()) }
}

/** Prefs access for [LyricsRepository]; faked in tests. */
interface LrcPrefs {
    fun treeUri(): String?
    fun filePaths(): List<String>?
}

/** Default [LrcPrefs] over SharedPreferences ("eighth_prefs"); paths joined with '\n'. */
class SharedPreferencesLrcPrefs(context: Context) : LrcPrefs {
    private val prefs = context.getSharedPreferences("eighth_prefs", Context.MODE_PRIVATE)

    override fun treeUri(): String? = prefs.getString("tree_uri", null)

    override fun filePaths(): List<String>? =
        prefs.getString("file_paths", null)?.split('\n')?.filter { it.isNotEmpty() }
}

/**
 * Port of the Flutter lyrics_service: resolves the .lrc directory, parses LRC,
 * fetches lyrics (lrclib.net -> NetEase -> lyrics.ovh) and bulk-syncs the library.
 */
class LyricsRepository(private val context: Context, private val prefs: LrcPrefs) {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private var cachedDir: File? = null
    private var triedMusicDir = false

    private fun lrcLog(message: String) {
        runCatching { Log.d("LRC", message) }
    }

    // ---- HTTP with retry (network error / timeout / 429 / 5xx, 600ms -> 1.2s -> 2.4s) ----

    private val retryDelaysMs = longArrayOf(600, 1200, 2400)

    /** Returns null when the attempts are exhausted. */
    private suspend fun lrcRequest(label: String, request: () -> Response): Response? {
        var attempt = 0
        while (true) {
            try {
                val res = withContext(Dispatchers.IO) { request() }
                if ((res.code == 429 || res.code >= 500) && attempt < retryDelaysMs.size) {
                    lrcLog("$label ${res.code}, retry ${attempt + 1}")
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

    // ---- LRC directory ----

    private val lrcFolderName = "8th_lrc"

    /**
     * Resolves the 8th_lrc directory with cache. Priority: music folder from
     * prefs (write-probed ONCE, failure remembered so scoped storage is not
     * re-probed per song) -> app-specific external dir -> legacy Flutter
     * internal dir when present (data compatibility) -> filesDir.
     */
    suspend fun getLrcDir(): File = withContext(Dispatchers.IO) {
        cachedDir?.takeIf { it.exists() }?.let { return@withContext it }

        if (!triedMusicDir) {
            var musicDir: String? = null
            try {
                val treeUri = prefs.treeUri()
                if (!treeUri.isNullOrEmpty()) {
                    if (treeUri.startsWith("/")) {
                        if (File(treeUri).isDirectory) {
                            musicDir = treeUri
                        } else {
                            val cleaned = treeUri.replaceFirst("file://", "")
                            if (File(cleaned).isDirectory) musicDir = cleaned
                        }
                    } else if (treeUri.startsWith("file://")) {
                        val cleaned = treeUri.replaceFirst("file://", "")
                        if (File(cleaned).isDirectory) musicDir = cleaned
                    }
                    // content:// is not writable as a plain File -> fallback below.
                }
                if (musicDir == null) {
                    val filePaths = prefs.filePaths()
                    if (!filePaths.isNullOrEmpty()) {
                        val parent = File(filePaths.first()).parent
                        if (parent != null && File(parent).isDirectory) {
                            musicDir = if (File(parent).name == lrcFolderName) File(parent).parent else parent
                        }
                    }
                }
            } catch (e: Exception) {
                lrcLog("getLrcDir resolve musicDir error: $e")
            }

            if (musicDir != null) {
                val lrcDir = File(musicDir, lrcFolderName)
                try {
                    if (!lrcDir.exists()) lrcDir.mkdirs()
                    // Probe writability once and cache the outcome.
                    val probe = File(lrcDir, ".probe")
                    probe.writeText("ok")
                    probe.delete()
                    lrcLog("LRC dir: ${lrcDir.path} (in music folder)")
                    cachedDir = lrcDir
                    triedMusicDir = true
                    return@withContext lrcDir
                } catch (e: Exception) {
                    // Scoped storage on Android 11+ blocks /storage/emulated/0/...
                    lrcLog("LRC probe failed in ${lrcDir.path}: $e -> fallback (cached)")
                    triedMusicDir = true
                }
            } else {
                triedMusicDir = true
            }
        }

        cachedDir?.takeIf { it.exists() }?.let { return@withContext it }

        // Fallback 2: app-specific external dir (no permission needed).
        try {
            val ext = context.getExternalFilesDir(null)
            if (ext != null) {
                val extLrc = File(ext, lrcFolderName)
                if (!extLrc.exists()) extLrc.mkdirs()
                cachedDir = extLrc
                return@withContext extLrc
            }
        } catch (_: Exception) {
        }

        // Fallback 3: reuse the Flutter build's internal dir when it exists,
        // otherwise the native equivalent (filesDir).
        val legacy = File(File(context.dataDir, "app_flutter"), lrcFolderName)
        if (legacy.exists()) {
            cachedDir = legacy
            return@withContext legacy
        }
        val fallback = File(context.filesDir, lrcFolderName)
        if (!fallback.exists()) fallback.mkdirs()
        cachedDir = fallback
        fallback
    }

    suspend fun lrcFileFor(song: Song): File =
        File(getLrcDir(), "${LrcLogic.baseNameFor(song)}.lrc")

    // ---- Cache presence ----

    suspend fun hasLrc(song: Song): Boolean = withContext(Dispatchers.IO) {
        try {
            val f = lrcFileFor(song)
            f.exists() && f.length() > 0
        } catch (_: Exception) {
            false
        }
    }

    /** Lists existing .lrc names (lowercase) ONCE so presence checks are O(1) per song. */
    suspend fun cachedLrcNames(): MutableSet<String> = withContext(Dispatchers.IO) {
        val names = mutableSetOf<String>()
        try {
            val dir = getLrcDir()
            if (dir.exists()) {
                dir.listFiles()?.forEach { e ->
                    if (e.isFile && e.path.lowercase().endsWith(".lrc")) names.add(e.name.lowercase())
                }
            }
        } catch (_: Exception) {
        }
        names
    }

    fun hasLrcIn(names: Set<String>, song: Song): Boolean =
        names.contains("${LrcLogic.baseNameFor(song).lowercase()}.lrc")

    /** Registers a freshly saved .lrc so it is not re-checked on disk. */
    fun registerLrcSaved(names: MutableSet<String>, song: Song) {
        names.add("${LrcLogic.baseNameFor(song).lowercase()}.lrc")
    }

    // ---- Loading ----

    suspend fun loadLrc(song: Song): List<LrcLine>? = withContext(Dispatchers.IO) {
        try {
            val file = lrcFileFor(song)
            if (!file.exists()) return@withContext null
            val content = file.readText()
            if (content.trim().isEmpty()) return@withContext null
            val parsed = LrcLogic.parseLrc(content)
            if (parsed.isEmpty()) {
                // Plain lyrics without timestamps -> unsynced lines (timestamp 0).
                return@withContext LrcLogic.plainLines(content).ifEmpty { null }
            }
            parsed
        } catch (_: Exception) {
            null
        }
    }

    // ---- Fetching (lrclib.net -> NetEase -> lyrics.ovh) ----

    private fun optStringField(o: JSONObject, key: String): String? =
        if (o.has(key) && !o.isNull(key)) o.optString(key) else null

    private fun jsonOrNull(body: String?): JSONObject? {
        if (body == null) return null
        return try { JSONObject(body) } catch (_: Exception) { null }
    }

    private fun arrayOrNull(body: String?): JSONArray? {
        if (body == null) return null
        return try { JSONArray(body) } catch (_: Exception) { null }
    }

    private suspend fun lrclibGet(label: String, url: String): Pair<Int, String?>? {
        val res = lrcRequest(label) {
            client.newCall(
                Request.Builder().url(url).header("User-Agent", "8th/1.0").build()
            ).execute()
        } ?: return null
        return res.code to res.body?.string()
    }

    private suspend fun tryGet(params: Map<String, String>): String? {
        return try {
            val url = HttpUrl.Builder()
                .scheme("https")
                .host("lrclib.net")
                .addPathSegments("api/get")
                .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
                .build()
            val (code, body) = lrclibGet("tryGet", url.toString()) ?: return null
            if (code == 200) {
                val json = jsonOrNull(body)
                if (json != null) {
                    val synced = optStringField(json, "syncedLyrics")
                    if (synced != null && synced.trim().isNotEmpty()) return synced
                    val plain = optStringField(json, "plainLyrics")
                    if (plain != null && plain.trim().isNotEmpty()) return plain
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun trySearchParams(artistName: String, trackName: String, durationSec: Int?): String? {
        return try {
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
            val (code, body) = lrclibGet("trySearch", url.toString()) ?: return null
            if (code != 200) return null
            val decoded = arrayOrNull(body) ?: return null
            val items = (0 until decoded.length()).mapNotNull { decoded.optJSONObject(it) }
            if (items.isEmpty()) return null
            fun durOk(item: JSONObject): Boolean {
                val d = if (item.has("duration") && !item.isNull("duration")) item.optLong("duration").toInt() else null
                return durationSec == null || d == null || abs(d - durationSec) <= 3
            }
            // Title-only mode demands an approximate duration to avoid another song.
            val candidates =
                if (artistName.isEmpty() && durationSec != null) items.filter { durOk(it) } else items
            for (item in candidates) {
                val synced = optStringField(item, "syncedLyrics")
                if (synced != null && synced.trim().isNotEmpty()) return synced
            }
            for (item in candidates) {
                val plain = optStringField(item, "plainLyrics")
                if (plain != null && plain.trim().isNotEmpty()) return plain
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Full cascade:
     * 1) lrclib /api/get with raw artist/track (+album/duration)
     * 2) lrclib /api/get with cleaned artist/track
     * 3) lrclib /api/search with raw data
     * 4) lrclib /api/search with cleaned data; 5) title-only search
     * 6) NetEase; 7) lyrics.ovh.
     */
    suspend fun fetchSyncedLyrics(song: Song): String? {
        val artist = (song.artist ?: "").trim()
        val title = song.displayTitle.trim()
        if (title.isEmpty()) return null
        val album = (song.album ?: "").trim()
        val durationSec: Int? = song.durationMs?.let { Math.round(it / 1000.0).toInt() }

        // 1) Exact attempt only with an artist (lrclib /get requires both).
        if (artist.isNotEmpty()) {
            val getParams = buildMap<String, String> {
                put("artist_name", artist)
                put("track_name", title)
                if (album.isNotEmpty()) put("album_name", album)
                if (durationSec != null) put("duration", durationSec.toString())
            }
            tryGet(getParams)?.let { return it }
            tryGet(mapOf("artist_name" to artist, "track_name" to title))?.let { return it }
        }

        // 2) Exact attempt with cleaned title/artist (no remaster, feat, etc.).
        val normTitle = LrcLogic.cleanTitle(title)
        val normArtist = if (artist.isEmpty()) "" else LrcLogic.cleanArtist(artist)
        if (normArtist.isNotEmpty() && (normTitle != title || normArtist != artist)) {
            tryGet(mapOf("artist_name" to normArtist, "track_name" to normTitle))?.let { return it }
        }

        // 3) Search with raw data.
        trySearchParams(artist, title, durationSec)?.let { return it }

        // 4) Search with cleaned data; 5) title-only (for mislabeled artists).
        if (normTitle != title || (normArtist.isNotEmpty() && normArtist != artist)) {
            trySearchParams(normArtist, normTitle, durationSec)?.let { return it }
            trySearchParams("", normTitle, durationSec)?.let { return it }
        }

        // 6) NetEase (synced LRC, good international coverage).
        fetchFromNetease(normArtist, normTitle, durationSec)?.let { return it }

        // 7) Secondary fallback: lyrics.ovh (plain).
        return fetchFromLyricsOvh(song)
    }

    private suspend fun fetchFromNetease(artist: String, title: String, durationSec: Int?): String? {
        if (title.isEmpty()) return null
        val userAgent = "Mozilla/5.0 (X11; Linux x86_64) 8th/1.0"
        return try {
            val q = listOf(artist, title).filter { it.isNotEmpty() }.joinToString(" ")
            val searchRes = lrcRequest("netease search") {
                val form = FormBody.Builder()
                    .add("s", q)
                    .add("type", "1")
                    .add("limit", "10")
                    .add("offset", "0")
                    .build()
                client.newCall(
                    Request.Builder()
                        .url("https://music.163.com/api/search/get")
                        .post(form)
                        .header("User-Agent", userAgent)
                        .header("Referer", "https://music.163.com")
                        .build()
                ).execute()
            } ?: return null
            if (searchRes.code != 200) return null
            val root = jsonOrNull(searchRes.body?.string()) ?: return null
            val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return null
            if (songs.length() == 0) return null

            var best: JSONObject? = null
            var bestScore = -1
            for (i in 0 until songs.length()) {
                val s = songs.optJSONObject(i) ?: continue
                val name = optStringField(s, "name") ?: ""
                val artistsArr = s.optJSONArray("artists")
                val artists = buildList {
                    if (artistsArr != null) {
                        for (j in 0 until artistsArr.length()) {
                            add(optStringField(artistsArr.optJSONObject(j) ?: continue, "name") ?: "")
                        }
                    }
                }.joinToString(", ")
                if (!LrcLogic.fuzzyContains(name, title)) continue
                if (artist.isNotEmpty() && !LrcLogic.fuzzyContains(artists, artist)) continue
                var score = 0
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
            val lyricRes = lrcRequest("netease lyric") {
                client.newCall(
                    Request.Builder()
                        .url("https://music.163.com/api/song/lyric?id=$id&lv=1&kv=1&tv=-1")
                        .header("User-Agent", userAgent)
                        .header("Referer", "https://music.163.com")
                        .build()
                ).execute()
            } ?: return null
            if (lyricRes.code != 200) return null
            val lyric = jsonOrNull(lyricRes.body?.string())
                ?.optJSONObject("lrc")
                ?.let { optStringField(it, "lyric") }
            val trimmed = lyric?.trim()
            // "纯音乐" = instrumental track, not lyrics.
            if (!trimmed.isNullOrEmpty() && !trimmed.contains("纯音乐")) trimmed else null
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
            val res = lrcRequest("ovh") {
                client.newCall(
                    Request.Builder().url(url).header("User-Agent", "8th/1.0").build()
                ).execute()
            } ?: return null
            if (res.code == 200) {
                val lyrics = jsonOrNull(res.body?.string())
                    ?.let { optStringField(it, "lyrics") }
                    ?.trim()
                if (!lyrics.isNullOrEmpty()) lyrics else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---- Save / purge / sync ----

    suspend fun fetchAndSave(song: Song): Boolean = withContext(Dispatchers.IO) {
        try {
            if (hasLrc(song)) return@withContext true
            val content = fetchSyncedLyrics(song)
            if (content == null || content.trim().isEmpty()) return@withContext false
            val file = lrcFileFor(song)
            file.writeText(content)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Deletes zero-byte .lrc files (half-failed downloads); returns the count. */
    suspend fun purgeEmptyLrc(): Int = withContext(Dispatchers.IO) {
        try {
            val dir = getLrcDir()
            if (!dir.exists()) return@withContext 0
            var n = 0
            dir.listFiles()?.forEach { e ->
                if (!e.isFile || !e.path.lowercase().endsWith(".lrc")) return@forEach
                try {
                    if (e.length() == 0L && e.delete()) n++
                } catch (_: Exception) {
                }
            }
            n
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Sequentially downloads missing .lrc files with a throttle. Aborts when
     * cancelled, or early when the network is down in series (>=15 misses with
     * no success). Returns the number of downloaded files.
     */
    suspend fun syncMissing(
        songs: List<Song>,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
        shouldCancel: (() -> Boolean)? = null,
    ): Int {
        if (songs.isEmpty()) return 0
        val total = songs.size
        // One directory listing for ALL presence checks.
        val lrcNames = cachedLrcNames()
        var done = 0
        var fetched = 0
        var consecutiveMisses = 0
        for (song in songs) {
            if (shouldCancel?.invoke() == true) break
            done++
            onProgress?.invoke(done, total)
            if (hasLrcIn(lrcNames, song)) continue
            val ok = fetchAndSave(song)
            if (ok) {
                fetched++
                consecutiveMisses = 0
                registerLrcSaved(lrcNames, song)
            } else {
                consecutiveMisses++
                if (consecutiveMisses >= 15 && fetched == 0) break
            }
            delay(350) // throttle only after an attempted fetch
            if (done % 10 == 0) delay(10) // yield periodically
        }
        runCatching { purgeEmptyLrc() }
        return fetched
    }
}
