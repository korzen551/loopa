package com.loopa.app.ui.feed

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.loopa.app.data.Playlist
import com.loopa.app.data.PlaylistEntry
import com.loopa.app.media.PlayerConnection
import com.loopa.app.ui.common.CheckRow
import com.loopa.app.ui.common.CountStepper
import com.loopa.app.ui.player.PlayerUiState

/**
 * Menu przewijania playlisty.
 *
 * Wszystko siedzi pod jednym wyłącznikiem: dopóki „automatyczne przewijanie" jest
 * odhaczone, reszta jest widoczna, ale wyblakła i nieklikalna — widać, że istnieje,
 * ale jeszcze nie działa.
 */
@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoAdvanceSheet(
    playlist: Playlist,
    entries: List<PlaylistEntry>,
    playerState: PlayerUiState,
    onSave: (Playlist) -> Unit,
    onItemCountChange: (itemId: Long, count: Int) -> Unit,
    onPreviewItem: (index: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember(playlist.id) { mutableStateOf(playlist) }
    var previewIndex by remember { mutableStateOf<Int?>(null) }

    val subEnabled = draft.autoAdvance

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            Text("Automatyczne przewijanie", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Wyłączone = playlista działa jak TikTok: film leci w kółko, " +
                    "a następny wybierasz palcem.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            CheckRow(
                checked = draft.autoAdvance,
                label = "Automatyczne przewijanie",
                description = "Włącznik główny wszystkiego poniżej",
                onCheckedChange = { draft = draft.copy(autoAdvance = it) },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            CheckRow(
                checked = draft.advanceOnFinish,
                label = "Przewijaj po zakończeniu",
                description = "Film kończy się i od razu leci następny",
                enabled = subEnabled,
                onCheckedChange = { draft = draft.copy(advanceOnFinish = it) },
            )

            CheckRow(
                checked = draft.useGlobalCount,
                label = "Każdy film tyle samo razy",
                description = "Jedna liczba dla całej playlisty",
                enabled = subEnabled,
                onCheckedChange = {
                    // Ta sama liczba dla wszystkich i liczba osobno dla każdego
                    // wykluczają się wzajemnie.
                    draft = draft.copy(useGlobalCount = it, usePerItemCount = if (it) false else draft.usePerItemCount)
                },
                trailing = {
                    CountStepper(
                        value = draft.globalCount,
                        enabled = subEnabled && draft.useGlobalCount,
                        onChange = { draft = draft.copy(globalCount = it) },
                    )
                },
            )

            CheckRow(
                checked = draft.usePerItemCount,
                label = "Ustaw każdy film osobno",
                description = "Np. pierwszy 2 razy, drugi 3, trzeci 20, czwarty raz",
                enabled = subEnabled,
                onCheckedChange = {
                    draft = draft.copy(usePerItemCount = it, useGlobalCount = if (it) false else draft.useGlobalCount)
                },
            )

            if (subEnabled && draft.usePerItemCount) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Dotknij film, żeby zobaczyć, o który chodzi",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))

                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    itemsIndexed(entries, key = { _, entry -> entry.item.id }) { index, entry ->
                        PerItemRow(
                            entry = entry,
                            position = index + 1,
                            total = entries.size,
                            expanded = previewIndex == index,
                            isPlayingThis = playerState.playlistItemId == entry.item.id,
                            playerState = playerState,
                            onToggleExpand = {
                                previewIndex = if (previewIndex == index) null else index
                                if (previewIndex == index) onPreviewItem(index)
                            },
                            onCountChange = { onItemCountChange(entry.item.id, it) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Anuluj") }
                Button(onClick = { onSave(draft) }, modifier = Modifier.weight(1f)) { Text("Zatwierdź") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Wiersz filmu w trybie indywidualnym. Po dotknięciu rozwija się podgląd, w którym
 * klip leci na małym ekranie — żeby nie było wątpliwości, któremu ustawiasz liczbę.
 */
@UnstableApi
@Composable
private fun PerItemRow(
    entry: PlaylistEntry,
    position: Int,
    total: Int,
    expanded: Boolean,
    isPlayingThis: Boolean,
    playerState: PlayerUiState,
    onToggleExpand: () -> Unit,
    onCountChange: (Int) -> Unit,
) {
    Surface(
        color = if (expanded) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggleExpand),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = entry.track.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 64.dp, height = 44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = entry.track.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Numer w playliście słabszym odcieniem, pod nazwą.
                    Text(
                        text = "Film $position z $total",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                Spacer(Modifier.width(8.dp))
                CountStepper(value = entry.item.playCount, onChange = onCountChange)
            }

            if (expanded) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.62f)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isPlayingThis && playerState.controller != null) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { context ->
                                PlayerView(context).apply {
                                    useController = false
                                    resizeMode =
                                        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                            },
                            update = { view -> view.player = playerState.controller },
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
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { PlayerConnection.togglePlayPause() }) {
                        Icon(
                            imageVector = if (playerState.isPlaying && isPlayingThis) {
                                Icons.Filled.Pause
                            } else {
                                Icons.Filled.PlayArrow
                            },
                            contentDescription = if (playerState.isPlaying) "Pauza" else "Graj",
                        )
                    }
                    Text(
                        text = "Poleci ${entry.item.playCount}× zanim przejdzie dalej",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
