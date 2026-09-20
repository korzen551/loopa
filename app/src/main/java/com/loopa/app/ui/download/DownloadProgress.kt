package com.loopa.app.ui.download

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.loopa.app.download.DownloadState

/**
 * Pasek postepu zapisu do galerii. Pokazuje sie sam, gdy kolejka pracuje,
 * i zostaje po skonczeniu z podsumowaniem, dopoki uzytkownik go nie zamknie.
 */
@Composable
fun DownloadProgressBar(
    state: DownloadState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is DownloadState.Idle -> Unit

        is DownloadState.Running -> Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 3.dp,
            modifier = modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Zapisuję do galerii…",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.total > 1) {
                        Text(
                            text = "${state.done + 1} z ${state.total}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { state.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        is DownloadState.Finished -> Surface(
            color = if (state.failed > 0) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            tonalElevation = 3.dp,
            modifier = modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = when {
                            state.failed == 0 && state.saved == 1 -> "Zapisano w galerii."
                            state.failed == 0 -> "Zapisano ${state.saved} filmów w galerii."
                            state.saved == 0 -> "Nie udało się zapisać."
                            else -> "Zapisano ${state.saved}, nie udało się ${state.failed}."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    state.firstError?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        }
    }
}
