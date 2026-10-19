package com.xly.u_8th.data

import android.content.Context
import com.xly.u_8th.domain.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Prefs access for [LyricsRepository]; faked in tests. */
interface LrcPrefs {
    fun treeUri(): String?
    fun filePaths(): List<String>?
}

/** Default [LrcPrefs] over [Prefs] (sin duplicar nombres de claves ni formato). */
class SharedPreferencesLrcPrefs(private val prefs: Prefs) : LrcPrefs {
    override fun treeUri(): String? = prefs.getTreeUri()

    override fun filePaths(): List<String>? = prefs.getFilePaths()
}

/**
 * Letras en disco: resuelve la carpeta .lrc, lee/guarda/importa ficheros,
 * gestiona el desfase y orquesta la descarga ([LyricsFetcher]) y la
 * sincronización masiva de la biblioteca. El parseo vive en [LrcLogic].
 */
class LyricsRepository(
    private val context: Context?,
    private val prefs: LrcPrefs,
    directory: File? = null,
    client: OkHttpClient = LyricsHttp.DefaultClient,
) {
    private companion object {
        /** Techo global por canción: 7 fuentes x 4 intentos se eternizan. */
        const val FETCH_TIMEOUT_MS = 90_000L
        const val INTERACTIVE_TIMEOUT_MS = 20_000L

        /** Tras un fallo, no se reintenta automáticamente durante este tiempo. */
        const val MISS_TTL_MS = 30 * 60_000L

        /** Descargas simultáneas de la sincronización masiva. */
        const val SYNC_WORKERS = 4

        /**
         * Ritmo global de la sincronización: un INICIO de
         * descarga cada 350 ms (el mismo throttle que la
         * versión secuencial; la latencia de red ya no
         * serializa las descargas).
         */
        const val SYNC_THROTTLE_MS = 350L

        /** Fallos seguidos sin éxito que abortan la sincronización. */
        const val SYNC_MISS_LIMIT = 15
    }
    private val fetcher = LyricsFetcher(LyricsHttp(client))
    private val interactiveFetcher = LyricsFetcher(
        LyricsHttp(client, LyricsHttp.INTERACTIVE_RETRIES_MS),
        parallel = true,
    )
    private val directoryLock = Mutex()
    private var cachedDir: File? = directory
    private var triedMusicDir = false

    /**
     * Un mutex por canción, no 64 globales: el bucketing por
     * `String.hashCode` colapsaba casi todos los `content://.../media/<id>`
     * en el mismo cubo (mismo prefijo) y serializaba la sincronización
     * masiva. Además el mutex se toma **antes** de las 7 peticiones con
     * reintentos, así que una colisión bloqueaba hasta ~60 s.
     * Confinado a [directoryLock]: `songLocks` solo crece con canciones
     * vistas, y cada entrada se elimina al terminar.
     */
    private val songLocks = HashMap<String, Mutex>()

    private suspend fun <T> withSongLock(song: Song, block: suspend () -> T): T {
        val key = song.path
        val mutex = directoryLock.withLock {
            songLocks.getOrPut(key) { Mutex() }
        }
        try {
            return mutex.withLock { block() }
        } finally {
            // NonCancellable: si el job ya está cancelado y el lock está
            // ocupado, `withLock` lanzaría y la entrada quedaría para siempre.
            withContext(NonCancellable) { directoryLock.withLock {
                // Solo se libera si el mutex sigue libre: alguien más pudo
                // tomar esta misma instancia mientras trabajábamos dentro.
                if (songLocks[key] === mutex && !mutex.isLocked) songLocks.remove(key)
            } }
        }
    }

    // ---- LRC directory ----

    private val lrcFolderName = "8th_lrc"

    /**
     * Resolves the 8th_lrc directory with cache. Priority: music folder from
     * prefs (write-probed once, failure remembered so scoped storage is not
     * re-probed per song) -> app-specific external dir -> legacy internal
     * dir when present (data compatibility) -> filesDir.
     *
     * The probe latch is released if the cached directory disappears (SD
     * unmounted, app data cleared): probing once per process was enough to
     * pin the app to a fallback the user cannot find, and to make existing
     * letters in the music folder invisible.
     */
    suspend fun getLrcDir(): File = directoryLock.withLock { resolveLrcDir() }

    private suspend fun resolveLrcDir(): File = withContext(Dispatchers.IO) {
        cachedDir?.takeIf { it.exists() }?.let { return@withContext it }
        if (cachedDir != null) triedMusicDir = false

        if (!triedMusicDir) {
            var musicDir: String? = null
            try {
                val treeUri = prefs.treeUri()
                if (!treeUri.isNullOrEmpty()) {
                    // content:// no es escribible como File plano -> fallback
                    // de abajo. Ambas ramas ^(file://)?(/\.\..*) convergen.
                    val asPath = treeUri.removePrefix("file://")
                    if (asPath.startsWith("/") && File(asPath).isDirectory) {
                        musicDir = asPath
                    }
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
            val ext = context?.getExternalFilesDir(null)
            if (ext != null) {
                val extLrc = File(ext, lrcFolderName)
                if (!extLrc.exists()) extLrc.mkdirs()
                cachedDir = extLrc
                return@withContext extLrc
            }
        } catch (_: Exception) {
        }

        // Fallback 3: reuse the previous app version's internal dir when it exists,
        // otherwise the native equivalent (filesDir).
        val legacy = context?.dataDir?.let { File(File(it, "app_flutter"), lrcFolderName) }
        if (legacy != null && legacy.exists()) {
            cachedDir = legacy
            return@withContext legacy
        }
        // Sin Context (solo en tests JVM) se cae al directorio temporal del sistema.
        val baseDir = context?.filesDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        val fallback = File(baseDir, lrcFolderName)
        if (!fallback.exists()) fallback.mkdirs()
        cachedDir = fallback
        fallback
    }

    suspend fun lrcFileFor(song: Song): File =
        File(getLrcDir(), "${LrcLogic.baseNameFor(song)}.lrc")

    // ---- Cache presence ----

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
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        names
    }

    fun hasLrcIn(names: Set<String>, song: Song): Boolean {
        // El set lo comparten los workers de syncMissing: lectura
        // y escritura concurrentes sobre un HashSet no son seguras.
        val key = "${LrcLogic.baseNameFor(song).lowercase()}.lrc"
        return synchronized(names) { names.contains(key) }
    }

    /** Registers a freshly saved .lrc so it is not re-checked on disk. */
    fun registerLrcSaved(names: MutableSet<String>, song: Song) {
        val key = "${LrcLogic.baseNameFor(song).lowercase()}.lrc"
        synchronized(names) { names.add(key) }
    }

    // ---- Loading ----

    suspend fun loadLrc(song: Song): List<LrcLine>? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        try {
            val file = lrcFileFor(song)
            if (!file.exists()) return@withContext null
            val content = file.readText()
            currentCoroutineContext().ensureActive()
            if (content.trim().isEmpty()) return@withContext null
            val parsed = LrcLogic.parseLrc(content, applyOffsetTag = true)
            currentCoroutineContext().ensureActive()
            if (parsed.isEmpty()) {
                // Plain lyrics without timestamps -> unsynced lines (timestamp 0).
                return@withContext LrcLogic.plainLines(content).ifEmpty { null }
            }
            parsed
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    // ---- Save / purge / sync ----

    private fun validatedLrc(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (LrcLogic.parseLrc(trimmed).isNotEmpty()) return trimmed
        return if (LrcLogic.plainLines(trimmed).isNotEmpty()) trimmed else null
    }

    private suspend fun writeAtomic(file: File, content: String): Boolean = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            try {
                tmp.writeText(content)
                currentCoroutineContext().ensureActive()
                when {
                    tmp.renameTo(file) -> true
                    file.exists() && !file.delete() -> false
                    else -> tmp.renameTo(file)
                }
            } finally {
                if (tmp.exists()) runCatching { tmp.delete() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Manual-control API: validates and saves [text] as the song's lyrics
     * ([offset:] tag allowed; timestamps or plain text). False when empty or
     * not lyrics-like.
     */
    suspend fun importLrc(song: Song, text: String): Boolean {
        currentCoroutineContext().ensureActive()
        val content = validatedLrc(text) ?: return false
        return withSongLock(song) { writeAtomic(lrcFileFor(song), content) }
    }

    /** Manual-control API: persisted per-song sync offset in ms from the .lrc [offset:] tag. */
    suspend fun getOffsetMs(song: Song): Long = withContext(Dispatchers.IO) {
        try {
            offsetTagFrom(fileHeader(lrcFileFor(song)))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            0L
        }
    }

    /** Manual-control API: persists [offsetMs] as the .lrc [offset:] tag (0 removes it). */
    suspend fun setOffsetMs(song: Song, offsetMs: Long): Unit = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        try {
            // Bajo el lock de la canción: una descarga/importación simultánea
            // podía reescribir el fichero entre la lectura y la escritura.
            withSongLock(song) {
                val file = lrcFileFor(song)
                if (file.exists()) {
                    val content = file.readText()
                    val updated = rewriteOffsetTag(content, offsetMs)
                    if (updated != content) writeAtomic(file, updated)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        Unit
    }

    // Patrones del tag [offset:] compilados una sola vez: se ejecutan por cada
    // canción en cada pasada de sincronización.
    private val OFFSET_TAG_FIND =
        Regex("""^\s*\[offset:\s*([+-]?\d+)\s*\]""", RegexOption.IGNORE_CASE)
    private val OFFSET_TAG_REMOVE = Regex("""(?im)^\s*\[offset:\s*[+-]?\d+\s*\]\s*\r?\n?""")
    private val OFFSET_TAG_REPLACE = Regex("""(?im)^(\s*)\[offset:\s*[+-]?\d+\s*\](.*)$""")

    internal fun offsetTagFrom(content: String): Long {
        val m = OFFSET_TAG_FIND.find(content) ?: return 0L
        return m.groupValues[1].toLongOrNull() ?: 0L
    }

    internal fun rewriteOffsetTag(content: String, offsetMs: Long): String {
        if (offsetMs == 0L) {
            return content.replace(OFFSET_TAG_REMOVE, "")
        }
        val tag = if (offsetMs > 0) "[offset:+$offsetMs]" else "[offset:$offsetMs]"
        return if (OFFSET_TAG_REPLACE.containsMatchIn(content)) {
            content.replace(OFFSET_TAG_REPLACE) { mr -> mr.groupValues[1] + tag + mr.groupValues[2] }
        } else {
            tag + (if (content.startsWith("\n") || content.isEmpty()) "" else "\n") + content
        }
    }

    private fun fileHeader(file: File, maxLines: Int = 24): String {
        if (!file.exists()) return ""
        return try {
            file.useLines { seq -> seq.take(maxLines).joinToString("\n") }
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Fallos recientes por canción (path → instante): abrir la pantalla de
     * letras de una canción sin letra relanzaba la cascada entera (hasta
     * ~10 peticiones) cada vez. Solo memoria; "Reintentar" lo ignora.
     */
    private val recentMisses = ConcurrentHashMap<String, Long>()

    private suspend fun fetchAndSaveInternal(song: Song, force: Boolean, interactive: Boolean): Boolean = withSongLock(song) {
        currentCoroutineContext().ensureActive()
        val file = lrcFileFor(song)
        var upgradingPlain = false
        if (file.exists()) {
            if (file.length() > 0) {
                // Con `force`, una letra sin sincronizar se intenta mejorar.
                if (!force) return@withSongLock true
                val existing = runCatching { file.readText() }.getOrDefault("")
                if (LrcLogic.hasTimestamps(existing)) return@withSongLock true
                upgradingPlain = true
            } else {
                file.delete()
            }
        }
        val now = System.currentTimeMillis()
        if (!force) {
            val missedAt = recentMisses[song.path]
            if (missedAt != null && now - missedAt < MISS_TTL_MS) return@withSongLock false
        }
        // El timeout va FUERA de recoverOrNull a propósito:
        // `TimeoutCancellationException` hereda de CancellationException, así
        // que dentro recoverOrNull la relanzaría y abortaría la sincronización
        // entera en vez de saltarse solo esta canción.
        val content = try {
            if (interactive) {
                withTimeout(INTERACTIVE_TIMEOUT_MS) { interactiveFetcher.fetch(song) }
            } else {
                withTimeout(FETCH_TIMEOUT_MS) { fetcher.fetch(song) }
            }
        } catch (e: TimeoutCancellationException) {
            lrcLog("timeout downloading lyrics for ${song.displayTitle}")
            null
        } catch (e: CancellationException) {
            // Cancelación real del job (no el timeout): debe propagarse.
            throw e
        } catch (e: Exception) {
            null
        }
        if (content.isNullOrBlank() || (upgradingPlain && !LrcLogic.hasTimestamps(content))) {
            recentMisses[song.path] = now
            return@withSongLock false
        }
        recentMisses.remove(song.path)
        writeAtomic(file, content)
    }

    /**
     * Descarga y guarda la letra si falta. [force] (acción "Reintentar")
     * ignora los fallos recientes y, si ya hay una letra sin sincronizar,
     * busca una versión sincronizada para sustituirla. [interactive] (la
     * pantalla de letras esperando) consulta todas las fuentes en paralelo
     * y apenas reintenta.
     */
    suspend fun fetchAndSave(song: Song, force: Boolean = false, interactive: Boolean = false): Boolean {
        currentCoroutineContext().ensureActive()
        return fetchAndSaveInternal(song, force, interactive)
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
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Descarga las .lrc faltantes con [SYNC_WORKERS] descargas en
     * paralelo y UN ritmo global de un inicio cada
     * [SYNC_THROTTLE_MS]: la misma cortesía con las APIs que la
     * versión secuencial, pero sin pagar la latencia de red entre
     * descargas (una red de 1 s pasaba de ~1.35 s a ~0.35 s por
     * canción). Aborta al cancelarse, o temprano con la red caída
     * (>= [SYNC_MISS_LIMIT] fallos seguidos sin éxito). Devuelve el
     * número de ficheros descargados.
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
        val doneCount = AtomicInteger(0)
        val fetchedCount = AtomicInteger(0)
        val missStreak = AtomicInteger(0)
        val aborted = AtomicBoolean(false)
        coroutineScope {
            // Tickets de arranque: el ticker se bloquea en `send`
            // si no hay descargas esperando, así que la cadencia
            // real nunca supera la original aunque la red sea
            // lenta (no se disparan ráfagas).
            val throttle = Channel<Unit>(Channel.RENDEZVOUS)
            val ticker = launch {
                while (isActive) {
                    delay(SYNC_THROTTLE_MS)
                    try {
                        throttle.send(Unit)
                    } catch (_: ClosedSendChannelException) {
                        break
                    }
                }
            }
            val gate = Semaphore(SYNC_WORKERS)
            val workers = songs.map { song ->
                launch {
                    gate.withPermit {
                        currentCoroutineContext().ensureActive()
                        if (aborted.get() || shouldCancel?.invoke() == true) return@withPermit
                        doneCount.incrementAndGet()
                        onProgress?.invoke(doneCount.get(), total)
                        if (hasLrcIn(lrcNames, song)) return@withPermit
                        if (aborted.get() || shouldCancel?.invoke() == true) return@withPermit
                        // `receiveCatching`: el cierre del canal
                        // (aborto por red caída) despierta a los
                        // que aún esperan ticket sin lanzar.
                        if (!throttle.receiveCatching().isSuccess) return@withPermit
                        if (aborted.get() || shouldCancel?.invoke() == true) return@withPermit
                        val ok = fetchAndSave(song)
                        if (ok) {
                            fetchedCount.incrementAndGet()
                            missStreak.set(0)
                            registerLrcSaved(lrcNames, song)
                        } else if (
                            missStreak.incrementAndGet() >= SYNC_MISS_LIMIT &&
                            fetchedCount.get() == 0
                        ) {
                            aborted.set(true)
                            // Cierra el canal: los que esperan
                            // ticket salen ya, sin esperar los
                            // 350 ms siguientes.
                            throttle.close()
                        }
                    }
                }
            }
            workers.forEach { it.join() }
            ticker.cancel()
        }
        // No `runCatching`: se tragaría la cancelación del job y la
        // sincronización seguiría tras un "cancelar".
        purgeEmptyLrc()
        return fetchedCount.get()
    }
}
