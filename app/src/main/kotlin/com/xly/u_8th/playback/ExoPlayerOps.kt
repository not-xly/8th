package com.xly.u_8th.playback

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.xly.u_8th.data.ArtCache
import com.xly.u_8th.domain.LoopMode
import com.xly.u_8th.domain.Song
import com.xly.u_8th.util.AppStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * [PlayerOps] implementation over a media3 [Player]. Items are built WITHOUT
 * blocking on artwork IO: the current item's cover is resolved lazily and
 * re-published via [setMediaItemArtwork].
 */
class ExoPlayerOps(
    private val player: Player,
    private val artCache: ArtCache,
    private val scope: CoroutineScope,
) : PlayerOps {

    override fun replaceItems(songs: List<Song>, startIndex: Int, startPositionMs: Long): Boolean {
        return try {
            player.setMediaItems(songs.map { mediaItemFor(it) }, startIndex, startPositionMs)
            // setMediaItems leaves the player idle: prepare() moves it to
            // buffering/ready so the subsequent play() actually starts.
            player.prepare()
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun addItem(song: Song, index: Int): Boolean {
        return try {
            player.addMediaItems(index, listOf(mediaItemFor(song)))
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun removeItemAt(index: Int) {
        runCatching { player.removeMediaItems(index, index + 1) }
    }

    override fun moveItem(from: Int, to: Int) {
        runCatching { player.moveMediaItem(from, to) }
    }

    override fun replaceAroundCurrent(songs: List<Song>, currentAt: Int): Boolean {
        return try {
            val current = player.currentMediaItemIndex
            val count = player.mediaItemCount
            if (current !in 0 until count) return false
            // En bloque: dos quitas y dos inserciones, la pista actual intacta.
            player.removeMediaItems(current + 1, count)
            player.removeMediaItems(0, current)
            player.addMediaItems(0, songs.subList(0, currentAt).map { mediaItemFor(it) })
            player.addMediaItems(currentAt + 1, songs.subList(currentAt + 1, songs.size).map { mediaItemFor(it) })
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun clearItems() {
        runCatching { player.clearMediaItems() }
    }

    override fun seekToIndex(index: Int): Boolean {
        return try {
            player.seekToDefaultPosition(index)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun seekTo(positionMs: Long) {
        runCatching { player.seekTo(positionMs) }
    }

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun prepareAfterError() {
        player.prepare()
    }

    override fun stopForEnd() {
        // End of queue in LoopMode.OFF: rewind and pause so the
        // notification/Auto keep a consistent state instead of "completed".
        runCatching {
            player.seekTo(0)
            player.pause()
        }
    }

    override val playing: Boolean
        get() = player.isPlaying

    override val positionMs: Long
        get() = player.currentPosition.coerceAtLeast(0L)

    override val durationMs: Long
        get() = if (player.duration == C.TIME_UNSET) 0L else player.duration

    override val currentIndex: Int
        get() = if (player.mediaItemCount == 0) -1 else player.currentMediaItemIndex

    override val itemCount: Int
        get() = player.mediaItemCount

    override fun setShuffleEnabled(enabled: Boolean) {
        player.shuffleModeEnabled = enabled
    }

    override val shuffleEnabled: Boolean
        get() = player.shuffleModeEnabled

    override fun setRepeatMode(mode: LoopMode) {
        player.repeatMode = when (mode) {
            LoopMode.OFF -> Player.REPEAT_MODE_OFF
            LoopMode.ALL -> Player.REPEAT_MODE_ALL
            LoopMode.ONE -> Player.REPEAT_MODE_ONE
        }
    }

    /**
     * Re-publishes the current item's metadata with its resolved cover. The
     * non-initial items of a queue are built without art (writing a temp
     * file per queue item would be expensive), so the notification/lock
     * screen would otherwise lose the cover when advancing tracks.
     */
    override fun setMediaItemArtwork(song: Song) {
        scope.launch {
            val uri = try {
                artCache.getArtUri(song)
            } catch (_: Exception) {
                null
            } ?: return@launch
            val index = player.currentMediaItemIndex
            val current = player.currentMediaItem ?: return@launch
            // Only still the same song: avoid republishing a stale item.
            if (current.mediaId != song.playUri) return@launch
            if (current.mediaMetadata.artworkUri != null) return@launch
            val metadata = current.mediaMetadata
                .buildUpon()
                .setArtworkUri(uri)
                .build()
            val rebuilt = current.buildUpon().setMediaMetadata(metadata).build()
            runCatching { player.replaceMediaItem(index, rebuilt) }
        }
    }

    /** Builds the media3 item for a song; art only from the cheap MediaStore URI. */
    private fun mediaItemFor(song: Song): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(song.displayTitle)
            .setArtist(song.artist ?: AppStrings.UNKNOWN)
            .setAlbumTitle(song.album ?: AppStrings.UNKNOWN)
            .apply {
                song.durationMs?.let { setDurationMs(it) }
                song.albumArtUri?.takeIf { it.isNotEmpty() }?.let { setArtworkUri(it.toUri()) }
            }
            .build()
        return MediaItem.Builder()
            .setMediaId(song.playUri)
            .setUri(song.playbackUriString)
            .setMediaMetadata(metadata)
            .build()
    }
}
