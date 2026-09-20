package com.loopa.app.ui.download

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.loopa.app.data.Track
import com.loopa.app.download.GalleryAlbum

/**
 * Pobieranie jednego filmu: gdzie w galerii ma trafic i ile z niego zapisac.
 *
 * Wybor albumu jest pojedynczy, bo w Androidzie album galerii to po prostu
 * folder, a jeden plik nie moze lezec w dwoch folderach naraz. Film i tak
 * zawsze pojawi sie w galerii - album decyduje tylko, w ktorym jej folderze.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSheet(
    track: Track,
    durationMs: Long,
    albums: List<GalleryAlbum>,
    albumsLoading: Boolean,
    onConfirm: (album: GalleryAlbum, startMs: Long, endMs: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selected by remember(albums) { mutableStateOf(albums.firstOrNull() ?: GalleryAlbum.Default) }
    var startMs by remember { mutableLongStateOf(0L) }
    var endMs by remember { mutableLongStateOf(0L) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            Text("Pobierz do galerii", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(16.dp))
            TrimControl(
                durationMs = durationMs,
                startMs = startMs,
                endMs = endMs,
                onChange = { s, e -> startMs = s; endMs = e },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Text("Album", style = MaterialTheme.typography.labelLarge)
            Text(
                "Film zawsze pojawi się w galerii — album decyduje tylko, w którym folderze.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))

            if (albumsLoading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("Szukam albumów…", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                LazyColumn(Modifier.heightIn(max = 220.dp)) {
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
                                        "${album.videoCount} filmów · ${album.relativePath}"
                                    } else {
                                        "nowy folder · ${album.relativePath}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "Obraz zostanie przekodowany do formatu 9:16, więc dłuższe filmy " +
                    "potrwają — to nie jest zwykłe kopiowanie pliku.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Anuluj") }
                Button(
                    onClick = { onConfirm(selected, startMs, endMs) },
                    enabled = !albumsLoading,
                    modifier = Modifier.weight(1f),
                ) { Text("Pobierz") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
