package com.loopa.app.ui.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.loopa.app.data.PlaylistEntry
import com.loopa.app.ui.appViewModel
import com.loopa.app.ui.common.EmptyState
import com.loopa.app.ui.common.ListRow
import com.loopa.app.ui.common.describe
import com.loopa.app.ui.common.formatTime
import com.loopa.app.ui.common.shortLabel
import com.loopa.app.ui.library.TextPromptDialog
import com.loopa.app.ui.player.LoopEditorSheet
import com.loopa.app.ui.player.LoopScope
import com.loopa.app.ui.player.MiniPlayer
import com.loopa.app.ui.player.rememberPlayerState

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    playlistId: Long,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    /** Otwiera playlistę w trybie przewijanego feedu, na wskazanym filmie. */
    onOpenFeed: (Int) -> Unit,
) {
    val vm: PlaylistViewModel = appViewModel(key = "playlist-$playlistId") {
        PlaylistViewModel(it, playlistId)
    }
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val player = rememberPlayerState()

    var renaming by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PlaylistEntry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(playlist?.name ?: "Playlista") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Wróć")
                    }
                },
                actions = {
                    IconButton(onClick = { renaming = true }) {
                        Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = "Zmień nazwę")
                    }
                },
            )
        },
        bottomBar = { MiniPlayer(player, onOpen = onOpenPlayer) },
        floatingActionButton = {
            if (entries.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { onOpenFeed(0) },
                    icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    text = { Text("Odtwórz") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (entries.isEmpty()) {
                EmptyState(
                    title = "Pusta playlista",
                    body = "Udostępnij tu film z TikToka albo YouTube — wybierzesz tę playlistę przy zapisie.",
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
                    itemsIndexed(entries, key = { _, entry -> entry.item.id }) { index, entry ->
                        EntryRow(
                            entry = entry,
                            isFirst = index == 0,
                            isLast = index == entries.lastIndex,
                            onPlay = { onOpenFeed(index) },
                            onEditLoop = { editing = entry },
                            onRemove = { vm.remove(entry.item.id) },
                            onUp = { vm.move(index, index - 1) },
                            onDown = { vm.move(index, index + 1) },
                        )
                    }
                }
            }
        }
    }

    if (renaming) {
        TextPromptDialog(
            title = "Zmień nazwę",
            label = "Nazwa playlisty",
            confirm = "Zapisz",
            initial = playlist?.name.orEmpty(),
            onDismiss = { renaming = false },
            onConfirm = { vm.rename(it); renaming = false },
        )
    }

    editing?.let { entry ->
        LoopEditorSheet(
            initial = entry.loop,
            durationMs = entry.track.durationMs,
            currentPositionMs = if (player.trackId == entry.track.id) player.positionMs else 0L,
            canScopeToItem = true,
            hasOverride = entry.item.hasLoopOverride,
            onSeek = { if (player.trackId == entry.track.id) player.controller?.seekTo(it) },
            onSave = { settings, scope ->
                when (scope) {
                    LoopScope.ITEM -> vm.saveItemLoop(entry.item.id, settings)
                    LoopScope.TRACK -> vm.saveTrackLoop(entry.track.id, settings)
                }
                editing = null
            },
            onClearOverride = { vm.clearItemLoop(entry.item.id); editing = null },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun EntryRow(
    entry: PlaylistEntry,
    isFirst: Boolean,
    isLast: Boolean,
    onPlay: () -> Unit,
    onEditLoop: () -> Unit,
    onRemove: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }

    ListRow(
        modifier = Modifier.clickable(onClick = onPlay),
        thumbUrl = entry.track.thumbnailUrl,
        badge = entry.track.source.shortLabel,
        title = entry.track.title,
        subtitle = listOfNotNull(
            entry.track.author,
            formatTime(entry.track.durationMs).takeIf { entry.track.durationMs > 0 },
            "własne ustawienia".takeIf { entry.item.hasLoopOverride },
        ).joinToString(" · "),
        accent = entry.loop.describe(),
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                IconButton(onClick = onEditLoop) {
                    Icon(Icons.Filled.Repeat, contentDescription = "Ustaw pętlę", Modifier.size(20.dp))
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Więcej")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (!isFirst) {
                            DropdownMenuItem(
                                text = { Text("W górę") },
                                leadingIcon = { Icon(Icons.Filled.ArrowUpward, null) },
                                onClick = { menu = false; onUp() },
                            )
                        }
                        if (!isLast) {
                            DropdownMenuItem(
                                text = { Text("W dół") },
                                leadingIcon = { Icon(Icons.Filled.ArrowDownward, null) },
                                onClick = { menu = false; onDown() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Usuń z playlisty") },
                            onClick = { menu = false; onRemove() },
                        )
                    }
                }
            }
        },
    )
}
