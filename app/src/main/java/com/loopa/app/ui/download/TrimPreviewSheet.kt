package com.loopa.app.ui.download

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.loopa.app.data.PlaylistEntry
import com.loopa.app.download.GalleryAlbum
import com.loopa.app.media.PlayerConnection
import com.loopa.app.ui.player.rememberPlayerState

/**
 * Podglad filmu przy wyborze, ile z niego zapisac.
 *
 * Klip leci na malym ekranie, zeby nie bylo watpliwosci, ktoremu ustawiasz
 * dlugosc - to ta sama zasada, co przy ustawianiu liczby powtorzen.
 */
@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimPreviewSheet(
    entry: PlaylistEntry,
    limitMs: Long,
    onLimitChange: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val player = rememberPlayerState()
    var current by remember(entry.item.id) { mutableLongStateOf(limitMs) }

    val isThisTrack = player.trackId == entry.track.id
    val duration = if (isThisTrack && player.durationMs > 0) player.durationMs else entry.track.durationMs

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            Text(
                text = entry.track.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .fillMaxWidth(0.5f)
                        .aspectRatio(9f / 16f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isThisTrack && player.controller != null) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { context ->
                                PlayerView(context).apply {
                                    useController = false
                                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                            },
                            update = { view -> view.player = player.controller },
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
                }

                Spacer(Modifier.size(12.dp))

                Column {
                    IconButton(onClick = { PlayerConnection.togglePlayPause() }) {
                        Icon(
                            imageVector = if (player.isPlaying && isThisTrack) {
                                Icons.Filled.Pause
                            } else {
                                Icons.Filled.PlayArrow
                            },
                            contentDescription = if (player.isPlaying) "Pauza" else "Graj",
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            TrimControl(
                durationMs = duration,
                limitMs = current,
                onLimitChange = {
                    current = it
                    onLimitChange(it)
                },
            )

            Spacer(Modifier.height(16.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Gotowe") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Wybor folderu w galerii przed zapisem. Wspolny dla zapisu pojedynczego filmu
 * i calej playlisty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumPickerSheet(
    count: Int,
    albums: List<GalleryAlbum>,
    albumsLoading: Boolean,
    onConfirm: (GalleryAlbum) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selected by remember(albums) { mutableStateOf(albums.firstOrNull() ?: GalleryAlbum.Default) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text("Gdzie zapisać?", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "$count " + when {
                    count == 1 -> "film trafi do galerii."
                    count % 10 in 2..4 && count % 100 !in 12..14 -> "filmy trafią do galerii."
                    else -> "filmów trafi do galerii."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            if (albumsLoading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("Szukam albumów…", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(albums, key = { it.relativePath }) { album ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { selected = album }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected.relativePath == album.relativePath,
                                onClick = { selected = album },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    text = if (album.videoCount > 0) {
                                        "${album.videoCount} filmów"
                                    } else {
                                        "nowy folder"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Anuluj") }
                Button(
                    onClick = { onConfirm(selected) },
                    enabled = !albumsLoading,
                    modifier = Modifier.weight(1f),
                ) { Text("Zapisz") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
