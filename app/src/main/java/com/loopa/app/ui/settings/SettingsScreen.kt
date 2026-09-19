package com.loopa.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.loopa.app.ui.appViewModel
import com.loopa.app.update.UpdateState

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm: SettingsViewModel = appViewModel { SettingsViewModel(it) }
    val url by vm.updateUrl.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val refreshMessage by vm.refreshMessage.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(refreshMessage) {
        refreshMessage?.let {
            snackbar.showSnackbar(it)
            vm.consumeRefreshMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ustawienia") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Wróć")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text("Wersja", style = MaterialTheme.typography.titleMedium)
            Text(
                "Loopa ${vm.installedVersion} (kod ${vm.installedCode})",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Aktualizacja", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Wklej adres nowej wersji, a apka pobierze ją sama i otworzy instalator. " +
                    "Instalacja po wierzchu zachowuje playlisty i ustawienia pętli — " +
                    "nic nie trzeba odinstalowywać.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = url,
                onValueChange = vm::setUpdateUrl,
                label = { Text("Adres aktualizacji (APK albo JSON)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            val busy = state is UpdateState.Checking || state is UpdateState.Downloading
            Button(
                onClick = { vm.update() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (busy) "Pracuję…" else "Aktualizuj")
            }

            Spacer(Modifier.height(12.dp))
            UpdateStatus(
                state = state,
                onGrantPermission = { vm.grantInstallPermission() },
                onInstallAgain = { vm.installAgain() },
                onDismiss = { vm.dismissState() },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Biblioteka", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "TikTok nie podaje długości klipu przy zapisie, a bez niej suwaki pętli " +
                    "nie mają właściwej skali. To dociąga brakujące długości.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { vm.refreshMetadata() },
                enabled = !refreshing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (refreshing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                }
                Spacer(Modifier.size(8.dp))
                Text("Odśwież dane filmów")
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun UpdateStatus(
    state: UpdateState,
    onGrantPermission: () -> Unit,
    onInstallAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is UpdateState.Idle -> Unit

        is UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
            Text("Sprawdzam…", style = MaterialTheme.typography.bodyMedium)
        }

        is UpdateState.Downloading -> Column {
            Text("Pobieram… ${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is UpdateState.ReadyToInstall -> Column {
            Text(
                "Pobrane. Jeśli instalator się nie otworzył, uruchom go ręcznie.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onInstallAgain) { Text("Zainstaluj") }
                OutlinedButton(onClick = onDismiss) { Text("Zamknij") }
            }
        }

        is UpdateState.UpToDate -> Text(
            "Masz już najnowszą wersję (${state.versionName}).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        is UpdateState.NeedsInstallPermission -> Column {
            Text(
                "Android potrzebuje zgody na instalowanie aplikacji z Loopa. " +
                    "Bez niej nie da się zaktualizować z poziomu apki.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onGrantPermission) { Text("Otwórz ustawienia") }
        }

        is UpdateState.Failed -> Column {
            Text(
                state.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onDismiss) { Text("Zamknij") }
        }
    }
}
