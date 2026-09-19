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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import com.loopa.app.ui.appViewModel
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

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(sharedText) { vm.accept(sharedText) }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
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
                    Text("Pobieram dane filmu…")
                }

                is ShareState.Failed -> {
                    Text(current.reason, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Zamknij") }
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
                            accent = null,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("Do których playlist?", style = MaterialTheme.typography.labelLarge)

                    LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(playlists, key = { it.id }) { playlist ->
                            val isIn = playlist.id in alreadyIn
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = isIn || playlist.id in selected,
                                    enabled = !isIn,
                                    onCheckedChange = { vm.toggle(playlist.id) },
                                )
                                Text(
                                    text = playlist.name + if (isIn) "  (już tam jest)" else "",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        item {
                            TextButton(onClick = { creating = true }) {
                                Icon(Icons.Filled.Add, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text("Nowa playlista")
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (selected.isEmpty()) {
                            "Bez zaznaczenia film wyląduje w bibliotece, poza playlistami."
                        } else {
                            "Trafi do ${selected.size} playlist."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { vm.commit { onClose() } },
                            enabled = !saving,
                            modifier = Modifier.weight(1f),
                        ) { Text("Zapisz") }
                        Button(
                            onClick = { vm.commit { onOpenApp() } },
                            enabled = !saving,
                            modifier = Modifier.weight(1f),
                        ) { Text("Zapisz i otwórz") }
                    }
                    Spacer(Modifier.height(24.dp))
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
}
