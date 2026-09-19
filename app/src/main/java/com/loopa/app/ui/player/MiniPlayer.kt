package com.loopa.app.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.loopa.app.media.PlayerConnection
import com.loopa.app.ui.common.RowMeta
import com.loopa.app.ui.common.Thumb
import com.loopa.app.ui.common.describe

@UnstableApi
@Composable
fun MiniPlayer(
    state: PlayerUiState,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state.hasMedia,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier,
    ) {
        Surface(
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            androidx.compose.foundation.layout.Column {
                val progress = if (state.durationMs > 0) {
                    (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                } else {
                    0f
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpen)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Thumb(state.artworkUrl, Modifier.size(width = 56.dp, height = 38.dp))
                    RowMeta(
                        title = state.title,
                        subtitle = state.artist,
                        accent = state.loop.describe(),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { PlayerConnection.togglePlayPause() }) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pauza" else "Graj",
                        )
                    }
                    IconButton(onClick = { state.controller?.seekToNextMediaItem() }) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Następny")
                    }
                    Spacer(Modifier.width(2.dp))
                }
            }
        }
    }
}
