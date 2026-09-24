package com.loopa.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.loopa.app.media.PlayerConnection
import com.loopa.app.ui.appViewModel
import com.loopa.app.ui.common.describe
import com.loopa.app.ui.common.formatTime

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(onBack: () -> Unit) {
    val vm: PlayerViewModel = appViewModel { PlayerViewModel(it) }
    val state = rememberPlayerState()
    val track by vm.track.collectAsStateWithLifecycle()
    val hasOverride by vm.hasOverride.collectAsStateWithLifecycle()
    val embedFallback by vm.embedFallback.collectAsStateWithLifecycle()

    var showEditor by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(state.trackId, state.playlistItemId) {
        vm.bindItemId(state.playlistItemId)
        vm.onNowPlayingChanged(state.trackId, state.playlistItemId)
    }
    LaunchedEffect(state.trackId, state.durationMs) {
        if (state.durationMs > 0 && state.isFullDuration) vm.rememberDuration(state.trackId, state.durationMs)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Odtwarzanie") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Wróć")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val embedUrl = vm.embedUrl()
                when {
                    embedFallback && embedUrl != null -> EmbedPlayer(embedUrl, Modifier.fillMaxSize())
                    state.controller != null -> {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { context ->
                                PlayerView(context).apply {
                                    useController = false
                                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                                }
                            },
                            update = { view -> view.player = state.controller },
                            // Odpięcie widoku gasi obraz, ale nie dźwięk — dlatego
                            // po zgaszeniu ekranu muzyka leci dalej.
                            onRelease = { view -> view.player = null },
                        )
                    }
                    else -> Text("Nic nie gra", color = Color.White)
                }
            }

            if (state.error != null) {
                ErrorBar(
                    message = state.error,
                    onRetry = { vm.retryStream() },
                    onEmbed = { vm.useEmbedFallback() },
                )
            }

            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(
                    state.title.ifBlank { "—" },
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    state.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.loop.describe()?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }

                Spacer(Modifier.height(12.dp))

                val duration = state.durationMs.takeIf { it > 0 } ?: 1L
                Slider(
                    value = scrubbing ?: (state.positionMs.toFloat() / duration).coerceIn(0f, 1f),
                    onValueChange = { scrubbing = it },
                    onValueChangeFinished = {
                        scrubbing?.let { state.controller?.seekTo((it * duration).toLong()) }
                        scrubbing = null
                    },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(state.positionMs), style = MaterialTheme.typography.labelMedium)
                    Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelMedium)
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { state.controller?.seekToPreviousMediaItem() }) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Poprzedni", Modifier.size(32.dp))
                    }
                    Spacer(Modifier.size(16.dp))
                    FilledIconButton(
                        onClick = { PlayerConnection.togglePlayPause() },
                        modifier = Modifier.size(64.dp),
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pauza" else "Graj",
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    Spacer(Modifier.size(16.dp))
                    IconButton(onClick = { state.controller?.seekToNextMediaItem() }) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Następny", Modifier.size(32.dp))
                    }
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = { showEditor = true },
                    enabled = track != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Repeat, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Ustaw pętlę i punkt startu")
                }
            }
        }
    }

    if (showEditor && track != null) {
        LoopEditorSheet(
            initial = state.loop,
            durationMs = if (state.durationMs > 0) state.durationMs else track!!.durationMs,
            currentPositionMs = state.positionMs,
            canScopeToItem = state.playlistItemId != null,
            hasOverride = hasOverride,
            onSeek = { state.controller?.seekTo(it) },
            onSave = { settings, scope ->
                vm.saveLoop(settings, scope)
                showEditor = false
            },
            onClearOverride = { vm.clearOverride(); showEditor = false },
            onDismiss = { showEditor = false },
        )
    }
}

@Composable
private fun ErrorBar(message: String, onRetry: () -> Unit, onEmbed: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(16.dp),
    ) {
        Text(
            message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            textAlign = TextAlign.Start,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRetry) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("Spróbuj ponownie")
            }
            OutlinedButton(onClick = onEmbed) { Text("Otwórz w odtwarzaczu serwisu") }
        }
    }
}
