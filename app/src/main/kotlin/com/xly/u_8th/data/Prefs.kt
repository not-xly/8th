package com.xly.u_8th.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * App preferences, mirroring the legacy shared_preferences keys so the
 * one-time migration is a plain copy (see [migrateLegacyPrefsIfNeeded]).
 *
 * Ordered lists ([KEY_FILE_PATHS], [KEY_ALBUM_ORDER]) are stored as a single
 * string joined with \n: a SharedPreferences StringSet loses order and the
 * saved order matters. Paths never contain \n.
 */
class Prefs(context: Context) {

    private val appContext = context.applicationContext

    private val prefs by lazy {
        appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    }

    // ---- tree_uri ----

    suspend fun getTreeUri(): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_TREE_URI, null)
    }

    suspend fun setTreeUri(uri: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_TREE_URI, uri).apply()
    }

    suspend fun clearTreeUri() = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY_TREE_URI).apply()
    }

    // ---- file_paths / album_order (ordered lists) ----

    suspend fun getFilePaths(): List<String>? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_FILE_PATHS, null)?.decodeListValue()
    }

    suspend fun setFilePaths(paths: List<String>) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_FILE_PATHS, paths.encodeListValue()).apply()
    }

    suspend fun getAlbumOrder(): List<String> = withContext(Dispatchers.IO) {
        prefs.getString(KEY_ALBUM_ORDER, null)?.decodeListValue() ?: emptyList()
    }

    suspend fun saveAlbumOrder(order: List<String>) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_ALBUM_ORDER, order.encodeListValue()).apply()
    }

    private fun List<String>.encodeListValue(): String = joinToString("\n")

    private fun String.decodeListValue(): List<String> =
        if (isEmpty()) emptyList() else split('\n')

    // ---- One-time migration from legacy installs ----

    /**
     * Imports tree_uri / file_paths / album_order from the legacy shared_preferences file written by
     * shared_preferences file, once. Keys already present in our prefs win
     * (never overwritten); any failure leaves the data keys untouched.
     */
    suspend fun migrateLegacyPrefsIfNeeded() = withContext(Dispatchers.IO) {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return@withContext
        try {
            val legacy = File(
                appContext.applicationInfo.dataDir,
                "shared_prefs/legacy shared preferences file",
            )
            if (legacy.isFile) {
                val values = readLegacyStrings(legacy)
                importString(values, "legacy tree_uri", KEY_TREE_URI)
                importList(values, "legacy file_paths", KEY_FILE_PATHS)
                importList(values, "legacy album_order", KEY_ALBUM_ORDER)
            }
        } catch (_: Exception) {
        } finally {
            // Always flag it: a half-failed migration must not retry on
            // every start (imports are missing-key-only, so idempotent).
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
        }
    }

    private fun importString(values: Map<String, String>, legacyKey: String, key: String) {
        val value = values[legacyKey] ?: return
        if (!prefs.contains(key)) prefs.edit().putString(key, value).apply()
    }

    private fun importList(values: Map<String, String>, legacyKey: String, key: String) {
        val raw = values[legacyKey] ?: return
        if (prefs.contains(key)) return
        val decoded = LegacyPrefsCodecs.decodeStringList(raw) ?: return
        prefs.edit().putString(key, decoded.encodeListValue()).apply()
    }

    /** Reads the `<string name="...">value</string>` entries of a prefs XML. */
    private fun readLegacyStrings(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val out = HashMap<String, String>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val name = el.getAttribute("name")
            if (name.isNotEmpty()) out[name] = el.textContent
        }
        return out
    }

    companion object {
        private const val FILE_NAME = "eighth_prefs"
        private const val KEY_TREE_URI = "tree_uri"
        private const val KEY_FILE_PATHS = "file_paths"
        private const val KEY_ALBUM_ORDER = "album_order"
        private const val KEY_MIGRATED = "migrated_v1"
    }
}

/**
 * Pure decoders for legacy Dart `shared_preferences` string-list values, so
 * the migration can be unit-tested on the JVM without Android classes.
 *
 * Legacy format: [LIST_PREFIX] followed by either
 * (a) "!" + JSON array of strings (newer Dart versions), or
 * (b) "!" + Base64 of a Java-serialized java.util.ArrayList<String> (older).
 */
object LegacyPrefsCodecs {

    /** Base64 of "This is the prefix for a list." — the Dart list marker. */
    const val LIST_PREFIX = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu"

    /** Decodes a legacy list value; null when [raw] is not a valid one. */
    fun decodeStringList(raw: String): List<String>? = try {
        if (!raw.startsWith(LIST_PREFIX)) {
            null
        } else {
            val rest = raw.substring(LIST_PREFIX.length)
            if (!rest.startsWith("!")) {
                null
            } else {
                val payload = rest.substring(1)
                if (payload.startsWith("[")) parseJsonStringArray(payload) else javaSerializedList(payload)
            }
        }
    } catch (_: Throwable) {
        // Includes NoClassDefFoundError on old APIs: migration just skips.
        null
    }

    /** Minimal parser for a JSON array of strings (no org.json on the JVM). */
    private fun parseJsonStringArray(s: String): List<String>? {
        var i = 0
        val n = s.length
        fun skipWs() {
            while (i < n && s[i] <= ' ') i++
        }
        skipWs()
        if (i >= n || s[i] != '[') return null
        i++
        val out = mutableListOf<String>()
        skipWs()
        if (i < n && s[i] == ']') return out
        while (true) {
            skipWs()
            if (i >= n || s[i] != '"') return null
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= n) return null
                when (val c = s[i]) {
                    '"' -> {
                        i++
                        break
                    }
                    '\\' -> {
                        i++
                        if (i >= n) return null
                        when (val e = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 5 > n) return null
                                val code = s.substring(i + 1, i + 5).toIntOrNull(16) ?: return null
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> return null
                        }
                        i++
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
            out.add(sb.toString())
            skipWs()
            if (i >= n) return null
            when (s[i]) {
                ',' -> i++
                ']' -> return out
                else -> return null
            }
        }
    }

    private fun javaSerializedList(payload: String): List<String>? {
        val bytes = java.util.Base64.getDecoder().decode(payload)
        java.io.ObjectInputStream(bytes.inputStream()).use { stream ->
            val list = stream.readObject() as? List<*> ?: return null
            return list.map { it as? String ?: return null }
        }
    }
}
