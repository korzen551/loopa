package com.loopa.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.loopa.app.data.PlaylistEntry
import com.loopa.app.data.effectivePlayCount
import com.loopa.app.media.PlayerConnection
import com.loopa.app.ui.appViewModel
import com.loopa.app.ui.common.EmptyState
import com.loopa.app.ui.player.LoopEditorSheet
import com.loopa.app.ui.player.LoopScope
import com.loopa.app.ui.player.rememberPlayerState

/**
 * Playlista jak w TikToku: jeden film na ekran, przewijanie palcem w pionie,
 * biezacy klip leci w kolko. Automatyczne przewijanie wlacza sie osobno,
 * w menu pod ikona suwakow.
 */
@UnstableApi
@Composable
fun FeedScreen(
    playlistId: Long,
    onBack: () -> Unit,
    startIndex: Int = 0,
) {
    val vm: FeedViewModel = appViewModel(key = "feed-$playlistId") { FeedViewModel(it, playlistId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val player = rememberPlayerState()

    var showLoopEditor by remember { mutableStateOf(false) }
    var showAutoAdvance by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(
        initialPage = startIndex,
        pageCount = { state.entries.size },
    )

    LaunchedEffect(state.entries.size) {
        if (state.entries.isNotEmpty()) vm.ensureQueue(pagerState.currentPage)
    }

    // Palec prowadzi odtwarzacz.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (state.entries.isNotEmpty()) vm.goTo(page)
        }
    }

    // ...a automatyczne przewijanie prowadzi widok.
    LaunchedEffect(player.queueIndex) {
        val index = player.queueIndex
        if (index >= 0 && index < state.entries.size && index != pagerState.currentPage) {
            pagerState.animateScrollToPage(index)
        }
    }

    LaunchedEffect(player.trackId, player.durationMs) {
        if (player.durationMs > 0) vm.rememberDuration(player.trackId, player.durationMs)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (state.entries.isEmpty()) {
            EmptyState(
                title = "Pusta playlista",
                body = "Udostępnij tu film z TikToka albo YouTube, żeby było co przewijać.",
                modifier = Modifier.fillMaxWidth().align(Alignment.Center),
            )
        } else {
            VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val entry = state.entries[page]
                FeedPage(
                    entry = entry,
                    position = page + 1,
                    total = state.entries.size,
                    playCount = effectivePlayCount(state.playlist, entry),
                    isCurrent = page == pagerState.settledPage,
                    isPlaying = player.isPlaying,
                    controllerAttached = player.controller != null,
                    onTogglePlay = { PlayerConnection.togglePlayPause() },
                    attachPlayer = { view -> view.player = player.controller },
                )
            }
        }

        // Bez tego awaria zrodla wygladala jak zwykly czarny ekran i nie bylo wiadomo,
        // czy cos sie laduje, czy juz nic z tego nie bedzie.
        player.error?.let { message ->
            Column(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = message,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.retryCurrent(pagerState.settledPage) }) {
                        Text("Spróbuj ponownie")
                    }
                }
            }
        }

        Row(
            Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Wróć", tint = Color.White)
            }
            Text(
                text = state.playlist?.name.orEmpty(),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showLoopEditor = true }) {
                Icon(Icons.Filled.Repeat, contentDescription = "Pętla", tint = Color.White)
            }
            IconButton(onClick = { showAutoAdvance = true }) {
                Icon(Icons.Filled.Tune, contentDescription = "Przewijanie", tint = Color.White)
            }
        }
    }

    val currentEntry: PlaylistEntry? = state.entries.getOrNull(pagerState.settledPage)

    if (showLoopEditor && currentEntry != null) {
        LoopEditorSheet(
            initial = currentEntry.loop,
            durationMs = if (player.durationMs > 0) player.durationMs else currentEntry.track.durationMs,
            currentPositionMs = player.positionMs,
            canScopeToItem = true,
            hasOverride = currentEntry.item.hasLoopOverride,
            onSeek = { player.controller?.seekTo(it) },
            onSave = { points, _ ->
                vm.saveLoopPoints(currentEntry, points, pagerState.settledPage)
                showLoopEditor = false
            },
            onClearOverride = {
                vm.clearLoopOverride(currentEntry.item.id, pagerState.settledPage)
                showLoopEditor = false
            },
            onDismiss = { showLoopEditor = false },
        )
    }

    if (showAutoAdvance) {
        state.playlist?.let { playlist ->
            AutoAdvanceSheet(
                playlist = playlist,
                entries = state.entries,
                onSave = { updated ->
                    vm.savePlaylistSettings(updated, pagerState.settledPage)
                    showAutoAdvance = false
                },
                onItemCountChange = { itemId, count ->
                    vm.setItemPlayCount(itemId, count, pagerState.settledPage)
                },
                onPreviewItem = { index -> vm.goTo(index) },
                playerState = player,
                onDismiss = { showAutoAdvance = false },
            )
        }
    }
}

@UnstableApi
@Composable
private fun FeedPage(
    entry: PlaylistEntry,
    position: Int,
    total: Int,
    playCount: Int?,
    isCurrent: Boolean,
    isPlaying: Boolean,
    controllerAttached: Boolean,
    onTogglePlay: () -> Unit,
    attachPlayer: (PlayerView) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(isCurrent) {
                detectTapGestures(onTap = { if (isCurrent) onTogglePlay() })
            },
        contentAlignment = Alignment.Center,
    ) {
        // Tylko biezaca strona dostaje odtwarzacz - pozostale pokazuja miniature,
        // zeby nie bic sie o powierzchnie do rysowania.
        if (isCurrent && controllerAttached) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                update = attachPlayer,
                onRelease = { view -> view.player = null },
            )
        } else {
            AsyncImage(
                model = entry.track.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (isCurrent && !isPlaying) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Graj",
                    tint = Color.White,
                    modifier = Modifier.size(44.dp),
                )
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = entry.track.title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Film $position z $total" + if (playCount == null) " · w kółko" else " · ${playCount}×",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
            )
            entry.track.author?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = it,
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
