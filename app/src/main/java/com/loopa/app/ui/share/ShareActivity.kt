package com.loopa.app.ui.share

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.loopa.app.MainActivity
import com.loopa.app.data.Playlist
import com.loopa.app.resolve.SpotifyClient
import com.loopa.app.resolve.SpotifyKind
import com.loopa.app.ui.appViewModel
import com.loopa.app.ui.common.CheckRow
import com.loopa.app.ui.common.RowMeta
import com.loopa.app.ui.common.Thumb
import com.loopa.app.ui.common.formatTime
import com.loopa.app.ui.common.shortLabel
import com.loopa.app.ui.library.TextPromptDialog
import com.loopa.app.ui.theme.LoopaTheme

/**
 * Wejście z „Udostępnij”. Klip zapisuje się od razu, a tutaj decydujesz tylko,
 * gdzie ma trafić — dlatego to półarkusz nad tym, z czego udostępniałeś, a nie
 * pełny ekran, który wyrzuca z poprzedniej aplikacji.
 *
 * Utwory, albumy i playlisty ze Spotify też tu trafiają: każdy utwór jest
 * wyszukiwany na YouTube i zapisywany jako zwykły film.
 */
@UnstableApi
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = extractSharedText(intent)

        setContent {
            LoopaTheme {
                ShareSheet(
                    sharedText = shared,
                    onClose = { finish() },
                    onOpenApp = {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        )
                        finish()
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    private fun extractSharedText(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
        Intent.ACTION_SEND_MULTIPLE ->
            intent.getStringArrayListExtra(Intent.EXTRA_TEXT)?.joinToString("\n")
        Intent.ACTION_VIEW -> intent.dataString
        else -> intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.dataString
    }
}

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareSheet(
    sharedText: String?,
    onClose: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val vm: ShareViewModel = appViewModel { ShareViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val alreadyIn by vm.alreadyIn.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val createNew by vm.createNew.collectAsStateWithLifecycle()
    val newName by vm.newName.collectAsStateWithLifecycle()

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    LaunchedEffect(sharedText) { vm.accept(sharedText) }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Zapisz w Loopa", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))

            when (val current = state) {
                is ShareState.Loading -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Text(current.message)
                }

                is ShareState.Failed -> {
                    Text(current.reason, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Zamknij") }
                    Spacer(Modifier.height(24.dp))
                }

                is ShareState.Ready -> {
                    val track = current.track
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Thumb(
                            track.thumbnailUrl,
                            Modifier.size(width = 96.dp, height = 64.dp),
                            track.source.shortLabel,
                        )
                        Spacer(Modifier.size(12.dp))
                        RowMeta(
                            title = track.title,
                            subtitle = listOfNotNull(
                                track.author,
                                formatTime(track.durationMs).takeIf { track.durationMs > 0 },
                            ).joinToString(" · "),
                            accent = current.note,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("Do których playlist?", style = MaterialTheme.typography.labelLarge)

                    PlaylistChoices(
                        playlists = playlists,
                        isChecked = { it in alreadyIn || it in selected },
                        isLocked = { it in alreadyIn },
                        onToggle = vm::toggle,
                        onNew = { creating = true },
                    )

                    Spacer(Modifier.height(8.dp))
                    Hint(
                        if (selected.isEmpty()) {
                            "Bez zaznaczenia film wyląduje w bibliotece, poza playlistami."
                        } else {
                            "Trafi do ${playlistCount(selected.size)}."
                        }
                    )
                    SaveButtons(
                        enabled = !saving,
                        onSave = { vm.commit { onClose() } },
                        onSaveAndOpen = { vm.commit { onOpenApp() } },
                    )
                }

                is ShareState.Importing -> ImportProgress(current, onCancel = onClose)

                is ShareState.CollectionReady -> {
                    val import = current.import
                    val foundCount = import.total - import.missing.size
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Thumb(import.coverUrl, Modifier.size(64.dp))
                        Spacer(Modifier.size(12.dp))
                        RowMeta(
                            title = import.name,
                            subtitle = listOfNotNull(
                                if (import.kind == SpotifyKind.ALBUM) "Album ze Spotify" else "Playlista ze Spotify",
                                import.owner,
                            ).joinToString(" · "),
                            accent = "Znalazłem na YouTube $foundCount z ${import.total} ${songsGenitive(import.total)}",
                            modifier = Modifier.weight(1f),
                        )
                    }

                    if (import.missing.isNotEmpty()) MissingSongs(import.missing.map { "${it.artistLine} – ${it.title}" })

                    if (import.mayBeTruncated) {
                        Spacer(Modifier.height(8.dp))
                        Hint(
                            "Spotify pokazuje publicznie tylko pierwsze ${SpotifyClient.PREVIEW_LIMIT} " +
                                "utworów playlisty, więc dalszych nie dało się wczytać."
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("Gdzie zapisać?", style = MaterialTheme.typography.labelLarge)

                    CheckRow(
                        checked = createNew,
                        label = "Nowa playlista „$newName”",
                        description = "W kolejności ze Spotify",
                        onCheckedChange = vm::setCreateNew,
                        trailing = { TextButton(onClick = { renaming = true }) { Text("Zmień nazwę") } },
                    )

                    PlaylistChoices(
                        playlists = playlists,
                        isChecked = { it in selected },
                        isLocked = { false },
                        onToggle = vm::toggle,
                        onNew = { creating = true },
                    )

                    Spacer(Modifier.height(8.dp))
                    val targets = selected.size + if (createNew) 1 else 0
                    Hint(
                        if (targets == 0) {
                            "Bez zaznaczenia utwory wylądują w bibliotece, poza playlistami."
                        } else {
                            "Utwory trafią do ${playlistCount(targets)}."
                        }
                    )
                    SaveButtons(
                        enabled = !saving,
                        onSave = { vm.commit { onClose() } },
                        onSaveAndOpen = { vm.commit { onOpenApp() } },
                    )
                }
            }
        }
    }

    if (creating) {
        TextPromptDialog(
            title = "Nowa playlista",
            label = "Nazwa",
            confirm = "Utwórz",
            onDismiss = { creating = false },
            onConfirm = { vm.createPlaylist(it); creating = false },
        )
    }

    if (renaming) {
        TextPromptDialog(
            title = "Nazwa nowej playlisty",
            label = "Nazwa",
            confirm = "Gotowe",
            initial = newName,
            onDismiss = { renaming = false },
            onConfirm = { vm.renameNew(it); renaming = false },
        )
    }
}

@Composable
private fun PlaylistChoices(
    playlists: List<Playlist>,
    isChecked: (Long) -> Boolean,
    isLocked: (Long) -> Boolean,
    onToggle: (Long) -> Unit,
    onNew: () -> Unit,
) {
    LazyColumn(Modifier.heightIn(max = 260.dp)) {
        items(playlists, key = { it.id }) { playlist ->
            val locked = isLocked(playlist.id)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = isChecked(playlist.id),
                    enabled = !locked,
                    onCheckedChange = { onToggle(playlist.id) },
                )
                Text(
                    text = playlist.name + if (locked) "  (już tam jest)" else "",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item {
            TextButton(onClick = onNew) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("Nowa playlista")
            }
        }
    }
}

@Composable
private fun ImportProgress(state: ShareState.Importing, onCancel: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Thumb(state.coverUrl, Modifier.size(64.dp))
        Spacer(Modifier.size(12.dp))
        RowMeta(
            title = state.name,
            subtitle = "Szukam na YouTube: ${state.done} z ${state.total}",
            accent = "Znaleziono ${state.found}",
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(16.dp))
    LinearProgressIndicator(
        progress = { if (state.total > 0) state.done.toFloat() / state.total else 0f },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Hint(
        "Spotify nie wypuszcza dźwięku poza swoją apkę, więc każdy utwór wyszukuję " +
            "na YouTube po tytule, wykonawcy i długości. Przy długiej playliście to chwila."
    )
    Spacer(Modifier.height(16.dp))
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Anuluj") }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun MissingSongs(lines: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "Ukryj brakujące" else "Czego nie znalazłem (${lines.size})")
    }
    if (expanded) {
        Column(Modifier.padding(start = 12.dp, bottom = 4.dp)) {
            lines.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SaveButtons(enabled: Boolean, onSave: () -> Unit, onSaveAndOpen: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onSave, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Zapisz") }
        Button(onClick = onSaveAndOpen, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Zapisz i otwórz") }
    }
    Spacer(Modifier.height(24.dp))
}

private fun playlistCount(count: Int): String = if (count == 1) "jednej playlisty" else "$count playlist"

/** "z 1 utworu", "z 50 utworów". */
private fun songsGenitive(count: Int): String = if (count == 1) "utworu" else "utworów"
