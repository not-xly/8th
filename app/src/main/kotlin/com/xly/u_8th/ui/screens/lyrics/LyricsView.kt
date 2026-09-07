package com.xly.u_8th.ui.screens.lyrics

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xly.u_8th.EighthApplication
import com.xly.u_8th.data.LrcLine
import com.xly.u_8th.domain.Song
import com.xly.u_8th.playback.PlayerConnection
import com.xly.u_8th.ui.screens.fullplayer.rememberPlayerConnection
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.ui.theme.animatedAura
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/**
 * Lyrics pipeline, local .lrc ->
 * one-shot network fetch -> synced/plain/missing rendering with line
 * highlighting and auto-scroll keeping the current line at ~40% height.
 */
@Composable
fun LyricsView(
    song: Song?,
    positionMs: Long,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember(context) { EighthApplication.get(context).lyricsRepository }
    val connection = rememberPlayerConnection()

    var state by remember { mutableStateOf<LyricsUiState>(LyricsUiState.Loading) }
    // Generation counter: cancels stale loads when the song changes.
    var loadGen by remember { mutableIntStateOf(0) }

    LaunchedEffect(song?.playUri) {
        val current = song
        if (current == null) {
            state = LyricsUiState.Missing
            return@LaunchedEffect
        }
        val gen = ++loadGen
        state = LyricsUiState.Loading
        var lines = repository.loadLrc(current)
        if (loadGen != gen) return@LaunchedEffect
        if (lines.isNullOrEmpty()) {
            // One fetch attempt when no local file exists.
            val fetched = repository.fetchAndSave(current)
            if (loadGen != gen) return@LaunchedEffect
            if (fetched) {
                lines = repository.loadLrc(current)
                if (loadGen != gen) return@LaunchedEffect
            }
        }
        state = when {
            lines.isNullOrEmpty() -> LyricsUiState.Missing
            lines.any { it.timestampMs > 0 } -> LyricsUiState.Loaded(lines, synced = true)
            else -> LyricsUiState.Loaded(lines, synced = false)
        }
    }

    Box(modifier) {
        when (val s = state) {
            LyricsUiState.Loading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = EighthTheme.TextSecondary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
            }
            LyricsUiState.Missing -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Rounded.Lyrics,
                    contentDescription = null,
                    tint = EighthTheme.TextTertiary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text("Letra no disponible", color = EighthTheme.TextTertiary, fontSize = 12.sp)
                Text("Se buscará automáticamente", color = EighthTheme.TextTertiary, fontSize = 10.sp)
            }
            is LyricsUiState.Loaded ->
                if (s.synced) {
                    SyncedLyricsList(lines = s.lines, positionMs = positionMs, connection = connection)
                } else {
                    PlainLyrics(lines = s.lines)
                }
        }
    }
}

private sealed interface LyricsUiState {
    data object Loading : LyricsUiState
    data object Missing : LyricsUiState
    data class Loaded(val lines: List<LrcLine>, val synced: Boolean) : LyricsUiState
}

@Composable
private fun PlainLyrics(lines: List<LrcLine>) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            lines.joinToString("\n") { it.text },
            color = EighthTheme.TextSecondary,
            fontSize = 13.sp,
            lineHeight = 19.5.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SyncedLyricsList(
    lines: List<LrcLine>,
    positionMs: Long,
    connection: PlayerConnection,
) {
    val aura = animatedAura()
    val listState = rememberLazyListState()
    val currentIndex = remember(lines, positionMs) { lastLineAtOrBefore(lines, positionMs) }

    // Pause auto-scroll for a grace period after the user drags the list.
    var suppressUntil by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                suppressUntil = System.currentTimeMillis() + 4000
            }
        }
    }

    var initialJumpDone by remember(lines) { mutableStateOf(false) }
    var lastScrolledIndex by remember(lines) { mutableIntStateOf(-1) }
    LaunchedEffect(lines, currentIndex) {
        if (lines.isEmpty() || currentIndex < 0) return@LaunchedEffect
        if (!initialJumpDone) {
            // Wait for the first layout pass, then jump straight to the
            // singing line without animation.
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }
                .filter { it }
                .first()
            val viewport = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
            listState.scrollToItem(currentIndex, (0.4f * viewport).roundToInt())
            initialJumpDone = true
            lastScrolledIndex = currentIndex
            return@LaunchedEffect
        }
        if (currentIndex == lastScrolledIndex) return@LaunchedEffect
        lastScrolledIndex = currentIndex
        if (System.currentTimeMillis() < suppressUntil) return@LaunchedEffect
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == currentIndex }
        val viewport = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
        if (info != null) {
            listState.animateScrollBy(
                info.offset - 0.4f * viewport,
                tween(320, easing = EighthTheme.EasingStandard),
            )
        } else {
            listState.scrollToItem(currentIndex)
            listState.animateScrollBy(
                0.4f * viewport,
                tween(320, easing = EighthTheme.EasingStandard),
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 24.dp),
        ) {
            itemsIndexed(lines) { index, line ->
                val isCurrent = index == currentIndex
                val isPast = index < currentIndex
                val textColor by animateColorAsState(
                    when {
                        isCurrent -> Color.White
                        isPast -> EighthTheme.TextSecondary
                        else -> EighthTheme.TextTertiary
                    },
                    tween(220, easing = EighthTheme.EasingOutCubic),
                    label = "lyricColor",
                )
                val scale by animateFloatAsState(
                    if (isCurrent) 1.05f else 1f,
                    tween(220, easing = EighthTheme.EasingOutCubic),
                    label = "lyricScale",
                )
                val lineBg by animateColorAsState(
                    if (isCurrent) aura.copy(alpha = 0.14f) else Color.Transparent,
                    tween(280, easing = EighthTheme.EasingOutCubic),
                    label = "lyricBg",
                )
                val lineBorder by animateColorAsState(
                    if (isCurrent) aura.copy(alpha = 0.22f) else Color.Transparent,
                    tween(280, easing = EighthTheme.EasingOutCubic),
                    label = "lyricBorder",
                )
                Box(
                    Modifier
                        .padding(vertical = 3.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(lineBg)
                        .border(BorderStroke(0.7.dp, lineBorder), RoundedCornerShape(10.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            connection.launch { seekTo(line.timestampMs) }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // Fixed typography for every line: only scale/color animate,
                    // so the current line never re-wraps .
                    Text(
                        line.text,
                        color = textColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 18.9.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                    )
                }
            }
        }
        // Edge fades.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(18.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(EighthTheme.GlassSurface.copy(alpha = 0.9f), Color.Transparent),
                    ),
                ),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(18.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, EighthTheme.GlassSurface.copy(alpha = 0.9f)),
                    ),
                ),
        )
    }
}

/** Last line whose timestamp is <= [positionMs] (binary search, -1 if none). */
private fun lastLineAtOrBefore(lines: List<LrcLine>, positionMs: Long): Int {
    var lo = 0
    var hi = lines.size - 1
    var idx = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].timestampMs <= positionMs) {
            idx = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return idx
}
