package com.xly.u_8th.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xly.u_8th.domain.Song
import com.xly.u_8th.ui.theme.EighthTheme
import com.xly.u_8th.util.AppStrings

@Composable
internal fun MiniArt(song: Song) {
    val art: ImageBitmap? = rememberArtBitmap(song.albumArt, targetSize = 46.dp)
    if (art != null) {
        Image(
            bitmap = art,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(EighthTheme.RadiusM)),
        )
    } else {
        ArtPlaceholder(
            modifier = Modifier.size(46.dp),
            radius = EighthTheme.RadiusM,
            icon = Icons.Rounded.MusicNote,
            iconTint = EighthTheme.TextSecondary,
        )
    }
}

@Composable
internal fun TitleColumn(song: Song) {
    Column {
        Text(
            song.displayTitle,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.5.sp,
            letterSpacing = (-0.2).sp,
            color = EighthTheme.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            song.artist ?: song.album ?: AppStrings.UNKNOWN,
            color = EighthTheme.TextSecondary,
            fontSize = 12.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun MiniIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(38.dp)
            .pressClickable(pressedScale = 0.8f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = EighthTheme.TextPrimary, modifier = Modifier.size(24.dp))
    }
}
