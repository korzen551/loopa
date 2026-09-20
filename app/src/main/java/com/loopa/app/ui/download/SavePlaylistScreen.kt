package com.loopa.app.ui.download

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import com.loopa.app.data.PlaylistEntry
import com.loopa.app.ui.appViewModel
import com.loopa.app.ui.common.EmptyState
import com.loopa.app.ui.common.formatTime

/**
 * Zapis playlisty do galerii: siatka filmow z zaznaczaniem.
 *
 * Wchodzac, wszystko jest zaznaczone i w calosci - zeby "zapisz playliste"
 * bylo jednym kliknieciem. "Wybierz filmy" odznacza wszystko i oddaje wybor
 * uzytkownikowi. Pod kazda miniatura jest "Edytuj", gdzie mozna obejrzec klip
 * i zdecydowac, ile z niego zapisac.
 */
@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavePlaylistScreen(
    playlistId: Long,
    onBack: () -> Unit,
) {
    val vm: SavePlaylistViewModel = appViewModel(key = "save-$playlistId") {
        SavePlaylistViewModel(it, playlistId)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val trims by vm.trims.collectAsStateWithLifecycle()
    val downloadState by vm.downloadState.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<PlaylistEntry?>(null) }
    var confirming by remember { mutableStateOf(false) }

    LaunchedEffect(state.entries) { vm.primeSelection() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.playlist?.name?.let { "Zapisz: $it" } ?: "Zapisz playlistę") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Wróć")
                    }
                },
            )
        },
        bottomBar = {
            Column {
                DownloadProgressBar(state = downloadState, onDismiss = { vm.acknowledgeDownload() })
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Zaznaczono ${selected.size} z ${state.entries.size}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = {
                                vm.loadAlbums()
                                confirming = true
                            },
                            enabled = selected.isNotEmpty() && downloadState !is com.loopa.app.download.DownloadState.Running,
                        ) { Text("Zapisz") }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { vm.clearSelection() }) { Text("Wybierz filmy") }
                TextButton(onClick = { vm.selectAll() }) { Text("Zaznacz wszystkie") }
            }

            if (state.entries.isEmpty()) {
                EmptyState(
                    title = "Pusta playlista",
                    body = "Nie ma tu jeszcze czego zapisywać.",
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.entries, key = { it.item.id }) { entry ->
                        SelectableVideoCard(
                            entry = entry,
                            isSelected = entry.item.id in selected,
                            trim = trims[entry.item.id] ?: Trim(),
                            onToggle = { vm.toggle(entry.item.id) },
                            onEdit = {
                                vm.preview(entry)
                                editing = entry
                            },
                        )
                    }
                }
            }
        }
    }

    editing?.let { entry ->
        val trim = vm.trimFor(entry.item.id)
        TrimPreviewSheet(
            entry = entry,
            startMs = trim.startMs,
            endMs = trim.endMs,
            onChange = { s, e -> vm.setTrim(entry.item.id, s, e) },
            onDismiss = { editing = null },
        )
    }

    if (confirming) {
        val albums by vm.albums.collectAsStateWithLifecycle()
        val albumsLoading by vm.albumsLoading.collectAsStateWithLifecycle()
        AlbumPickerSheet(
            count = selected.size,
            albums = albums,
            albumsLoading = albumsLoading,
            onConfirm = { album ->
                vm.save(album)
                confirming = false
            },
            onDismiss = { confirming = false },
        )
    }
}

/**
 * Kafelek filmu. Zaznaczony dostaje obwodke, ptaszek w rogu i lekkie zmniejszenie
 * - tak jak zaznaczanie zdjec w galerii telefonu.
 */
@Composable
private fun SelectableVideoCard(
    entry: PlaylistEntry,
    isSelected: Boolean,
    trim: Trim,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
) {
    val scale by animateFloatAsState(if (isSelected) 0.94f else 1f, label = "scale")
    val borderWidth by animateDpAsState(if (isSelected) 3.dp else 0.dp, label = "border")

    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
                .scale(scale)
                .clip(RoundedCornerShape(14.dp))
                .border(
                    BorderStroke(borderWidth, MaterialTheme.colorScheme.primary),
                    RoundedCornerShape(14.dp),
                )
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onToggle),
        ) {
            AsyncImage(
                model = entry.track.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            if (isSelected) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)))
            }

            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else Color.Black.copy(alpha = 0.35f)
                    )
                    .border(
                        BorderStroke(1.5.dp, Color.White.copy(alpha = if (isSelected) 0f else 0.8f)),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Zaznaczony",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Text(
                text = if (!trim.isWholeVideo) {
                    val end = if (trim.endMs > 0L) trim.endMs else entry.track.durationMs
                    "${formatTime((end - trim.startMs).coerceAtLeast(0L))} z ${formatTime(entry.track.durationMs)}"
                } else {
                    formatTime(entry.track.durationMs)
                },
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        Text(
            text = entry.track.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )

        TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.size(4.dp))
            Text("Edytuj", style = MaterialTheme.typography.labelMedium)
        }
    }
}
