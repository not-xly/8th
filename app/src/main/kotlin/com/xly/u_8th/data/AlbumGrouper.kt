package com.xly.u_8th.data

import com.xly.u_8th.domain.Song
import com.xly.u_8th.util.AppStrings

/**
 * Pure album grouping/sorting helpers, shared by the UI grid, the search
 * index and the artist menus.
 */
object AlbumGrouper {

    /** Track number: tag first, then leading title digits, else 999. */
    fun resolveTrack(s: Song): Int {
        s.trackNumber?.let { return it }
        val t = s.title ?: s.displayTitle
        val m = Regex("""^(\d+)""").find(t)
        return m?.groupValues?.get(1)?.toIntOrNull() ?: 999
    }

    /**
     * Groups songs into albums.
     *
     * Step 1: group by normalized album name (case/whitespace insensitive).
     * Step 2: if the same name is used by different primary artists
     * ("Greatest Hits" by Queen and by ABBA), split per artist. Feats
     * ("Eminem" + "Eminem feat. Rihanna") share the primary artist and do
     * NOT split; a strict majority of one primary artist also counts as a
     * single album with collaborations.
     */
    fun groupByAlbum(songs: List<Song>): Map<String, List<Song>> {
        val unknown = AppStrings.UNKNOWN
        val byName = LinkedHashMap<String, MutableList<Song>>()
        val displayName = HashMap<String, String>()
        for (s in songs) {
            val raw = s.album?.trim()?.takeIf { it.isNotEmpty() } ?: unknown
            val key = raw.lowercase()
            byName.getOrPut(key) { mutableListOf() }.add(s)
            displayName.putIfAbsent(key, raw)
        }

        val map = LinkedHashMap<String, MutableList<Song>>()
        fun emit(visible: String, tracks: List<Song>) {
            var unique = visible
            var i = 2
            while (map.containsKey(unique)) {
                unique = "$visible ($i)"
                i++
            }
            map[unique] = tracks.toMutableList()
        }

        for ((key, tracks) in byName) {
            val albumDisplay = displayName[key]!!
            if (albumDisplay == unknown) {
                emit(unknown, tracks)
                continue
            }
            // Primary artist per track (lowercased; "" when missing).
            val principals = ArrayList<String>(tracks.size)
            val principalDisplay = LinkedHashMap<String, String>()
            for (t in tracks) {
                val p = Artists.primaryArtist(t.artist)
                val k = p?.lowercase() ?: ""
                principals.add(k)
                if (k.isNotEmpty()) principalDisplay.putIfAbsent(k, p!!)
            }
            val distinct = principals.filter { it.isNotEmpty() }.toSet()
            if (distinct.size <= 1) {                emit(albumDisplay, tracks)
                continue
            }
            // Is there a dominant primary (strict majority)? Then it is one
            // album with collaborations, not two different albums.
            val counts = HashMap<String, Int>()
            for (p in principals) {
                if (p.isEmpty()) continue
                counts[p] = (counts[p] ?: 0) + 1
            }
            var dominant = ""
            var dominantCount = 0
            for ((k, v) in counts) {
                if (v > dominantCount) {
                    dominant = k
                    dominantCount = v
                }
            }
            val withPrincipal = counts.values.fold(0) { a, b -> a + b }
            if (dominant.isNotEmpty() && dominantCount * 2 > withPrincipal) {
                emit(albumDisplay, tracks)
                continue
            }
            // Genuinely different artists with the same name: split.
            val buckets = LinkedHashMap<String, MutableList<Song>>()
            for (i in tracks.indices) {
                buckets.getOrPut(principals[i]) { mutableListOf() }.add(tracks[i])
            }
            val noArtist = buckets.remove("") ?: mutableListOf()
            val orderedKeys = buckets.keys.sortedByDescending { buckets[it]!!.size }
            for ((i, k) in orderedKeys.withIndex()) {
                val bucket = buckets[k]!!
                val group = if (i == 0 && noArtist.isNotEmpty()) bucket + noArtist else bucket
                emit("$albumDisplay — ${principalDisplay[k] ?: k}", group)
            }
            if (orderedKeys.isEmpty() && noArtist.isNotEmpty()) {
                emit(albumDisplay, noArtist.toList())
            }
        }

        for ((key, value) in map) {
            value.sortWith(
                compareBy<Song> { resolveTrack(it) }
                    .thenBy { it.displayTitle.lowercase() }
            )
        }
        val sortedKeys = map.keys.sortedWith(
            compareBy<String> { if (it == unknown) 1L else 0L }.thenBy { it.lowercase() }
        )
        val result = LinkedHashMap<String, List<Song>>()
        for (key in sortedKeys) {
            result[key] = map[key]!!
        }
        return result
    }

    /**
     * Orders album keys by [albumOrder] first, then keeps the grouped map's
     * insertion order for the rest. O(n) via a set.
     */
    fun sortedAlbumKeys(grouped: Map<String, List<Song>>, albumOrder: List<String>): List<String> {
        val remaining = grouped.keys.toMutableSet()
        val ordered = mutableListOf<String>()
        for (a in albumOrder) {
            if (remaining.remove(a)) ordered.add(a)
        }
        for (k in grouped.keys) {
            if (k in remaining) ordered.add(k)
        }
        return ordered
    }
}
