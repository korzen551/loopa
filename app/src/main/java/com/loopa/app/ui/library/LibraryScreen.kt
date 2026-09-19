package com.loopa.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.loopa.app.data.PlaylistWithCount
import com.loopa.app.data.Track
import com.loopa.app.ui.appViewModel
import com.loopa.app.ui.common.EmptyState
import com.loopa.app.ui.common.ListRow
import com.loopa.app.ui.common.Thumb
import com.loopa.app.ui.common.describe
import com.loopa.app.ui.common.formatTime
import com.loopa.app.ui.common.shortLabel
import com.loopa.app.ui.player.MiniPlayer
import com.loopa.app.ui.player.rememberPlayerState

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenPlaylist: (Long) -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm: LibraryViewModel = appViewModel { LibraryViewModel(it) }
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val loose by vm.loose.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val player = rememberPlayerState()

    var showNewPlaylist by remember { mutableStateOf(false) }
    var showAddLink by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Loopa") },
                actions = {
                    IconButton(onClick = { showAddLink = true }) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = "Wklej link")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Ustawienia")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { MiniPlayer(player, onOpen = onOpenPlayer) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewPlaylist = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Playlista") },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                item {
                    SectionHeader("Playlisty", "${playlists.size}")
                }
                if (playlists.isEmpty()) {
                    item {
                        EmptyState(
                            title = "Brak playlist",
                            body = "Zrób pierwszą, a potem wysyłaj do niej filmy przez „Udostępnij”.",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                items(playlists, key = { it.playlist.id }) { entry ->
                    PlaylistRow(
                        entry = entry,
                        onClick = { onOpenPlaylist(entry.playlist.id) },
                        onDelete = { vm.deletePlaylist(entry.playlist.id) },
                    )
                }

                item {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    SectionHeader("Filmy poza playlistami", "${loose.size}")
                }
                if (loose.isEmpty()) {
                    item {
                        EmptyState(
                            title = "Nic tu nie leży",
                            body = "Udostępnij film z TikToka albo YouTube do Loopa — wyląduje tutaj lub od razu w playliście.",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                items(loose, key = { it.id }) { track ->
                    TrackRow(
                        track = track,
                        onPlay = { vm.playAllLoose(loose.indexOf(track)); onOpenPlayer() },
                        onDelete = { vm.deleteTrack(track) },
                        onAddTo = { playlistId -> vm.addToPlaylist(playlistId, track.id) },
                        playlists = playlists,
                    )
                }
            }

            if (busy) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    if (showNewPlaylist) {
        TextPromptDialog(
            title = "Nowa playlista",
            label = "Nazwa",
            confirm = "Utwórz",
            onDismiss = { showNewPlaylist = false },
            onConfirm = { vm.createPlaylist(it); showNewPlaylist = false },
        )
    }

    if (showAddLink) {
        TextPromptDialog(
            title = "Wklej link",
            label = "Link do YouTube lub TikToka",
            confirm = "Dodaj",
            keyboardType = KeyboardType.Uri,
            onDismiss = { showAddLink = false },
            onConfirm = { vm.addLink(it); showAddLink = false },
        )
    }
}

@Composable
private fun SectionHeader(title: String, trailing: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlaylistRow(
    entry: PlaylistWithCount,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(entry.coverUrl, Modifier.size(56.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.playlist.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${entry.itemCount} ${plural(entry.itemCount)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Więcej")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Usuń playlistę") },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: Track,
    playlists: List<PlaylistWithCount>,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onAddTo: (Long) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }

    ListRow(
        modifier = Modifier.clickable(onClick = onPlay),
        thumbUrl = track.thumbnailUrl,
        badge = track.source.shortLabel,
        title = track.title,
        subtitle = listOfNotNull(track.author, formatTime(track.durationMs).takeIf { track.durationMs > 0 })
            .joinToString(" · "),
        accent = track.loop.describe(),
        trailing = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Więcej")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Dodaj do playlisty") },
                        leadingIcon = { Icon(Icons.Filled.PlaylistAdd, null) },
                        onClick = { menu = false; addMenu = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Usuń z biblioteki") },
                        onClick = { menu = false; onDelete() },
                    )
                }
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    if (playlists.isEmpty()) {
                        DropdownMenuItem(text = { Text("Najpierw utwórz playlistę") }, onClick = { addMenu = false })
                    }
                    playlists.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.playlist.name) },
                            onClick = { addMenu = false; onAddTo(p.playlist.id) },
                        )
                    }
                }
            }
        },
    )
}

@Composable
fun TextPromptDialog(
    title: String,
    label: String,
    confirm: String,
    initial: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

private fun plural(count: Int): String = when {
    count == 1 -> "film"
    count % 10 in 2..4 && count % 100 !in 12..14 -> "filmy"
    else -> "filmów"
}
