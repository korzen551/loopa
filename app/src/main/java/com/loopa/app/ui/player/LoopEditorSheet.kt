package com.loopa.app.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.loopa.app.data.LoopSettings
import com.loopa.app.data.RepeatMode
import com.loopa.app.ui.common.CountStepper
import com.loopa.app.ui.common.formatTime

/** Gdzie zapisać ustawienia po zatwierdzeniu. */
enum class LoopScope {
    /** Tylko ten wpis w tej playliście. */
    ITEM,

    /** Domyślnie dla filmu — wszędzie, gdzie nie ma własnego nadpisania. */
    TRACK,
}

/**
 * Edytor pętli.
 *
 * Trzy suwaki zamiast jednego, bo film ma tu dwa różne początki: ten pierwszy
 * i ten po każdym powtórzeniu. Klip 18-sekundowy może zagrać raz od zera,
 * a potem w kółko od 9. sekundy.
 *
 * Nic nie dzieje się w trakcie przesuwania — zmiana wchodzi w życie dopiero po
 * naciśnięciu „Zatwierdź". „Posłuchaj" pozwala sprawdzić wybrany moment bez
 * zapisywania czegokolwiek.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoopEditorSheet(
    initial: LoopSettings,
    durationMs: Long,
    currentPositionMs: Long,
    /** False, gdy film nie należy do playlisty — wtedy zapis idzie tylko do filmu. */
    canScopeToItem: Boolean,
    hasOverride: Boolean,
    onSeek: (Long) -> Unit,
    onSave: (LoopSettings, LoopScope) -> Unit,
    onClearOverride: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var settings by remember { mutableStateOf(initial) }
    var scope by remember { mutableStateOf(if (canScopeToItem) LoopScope.ITEM else LoopScope.TRACK) }

    val durationKnown = durationMs > 0L
    // Bez znanej długości suwak nie ma do czego się odnieść. Dajemy zastępczy
    // zakres, ale wartości i tak zostaną przycięte przy zapisie.
    val max = (if (durationKnown) durationMs else 60_000L).toFloat()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Ustawienia pętli", style = MaterialTheme.typography.titleLarge)
            Text(
                if (durationKnown) {
                    "Długość filmu: ${formatTime(durationMs)}"
                } else {
                    "Długość filmu jeszcze nieznana — włącz go na chwilę, żeby suwaki " +
                        "dostały właściwą skalę."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (durationKnown) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Spacer(Modifier.height(8.dp))

            TimeSlider(
                label = "Start pierwszego odtworzenia",
                valueMs = settings.startMs,
                maxMs = max,
                currentPositionMs = currentPositionMs,
                onChange = { settings = settings.copy(startMs = it) },
                onListen = onSeek,
            )

            TimeSlider(
                label = "Start każdego powtórzenia",
                hint = "Tu wraca film zamiast na sam początek",
                valueMs = settings.loopStartMs,
                maxMs = max,
                currentPositionMs = currentPositionMs,
                onChange = { settings = settings.copy(loopStartMs = it) },
                onListen = onSeek,
            )

            TimeSlider(
                label = "Koniec segmentu",
                hint = if (settings.endMs == 0L) "0:00 = do końca filmu" else null,
                valueMs = settings.endMs,
                maxMs = max,
                currentPositionMs = currentPositionMs,
                onChange = { settings = settings.copy(endMs = it) },
                onListen = onSeek,
            )

            Spacer(Modifier.height(8.dp))
            Text("Powtarzanie", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip("Raz", settings.repeatMode == RepeatMode.OFF) {
                    settings = settings.copy(repeatMode = RepeatMode.OFF)
                }
                ModeChip("W kółko", settings.repeatMode == RepeatMode.LOOP) {
                    settings = settings.copy(repeatMode = RepeatMode.LOOP)
                }
                ModeChip("Ile razy…", settings.repeatMode == RepeatMode.COUNT) {
                    settings = settings.copy(repeatMode = RepeatMode.COUNT)
                }
            }

            if (settings.repeatMode == RepeatMode.COUNT) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Odtworzeń:", Modifier.weight(1f))
                    CountStepper(
                        value = settings.repeatCount,
                        onChange = { settings = settings.copy(repeatCount = it) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            if (canScopeToItem) {
                Text("Zapisz jako", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip("Tylko w tej playliście", scope == LoopScope.ITEM) { scope = LoopScope.ITEM }
                    ModeChip("Domyślne dla filmu", scope == LoopScope.TRACK) { scope = LoopScope.TRACK }
                }
                if (hasOverride) {
                    TextButton(onClick = onClearOverride) {
                        Text("Usuń ustawienia tej playlisty")
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { settings = LoopSettings.Default },
                    modifier = Modifier.weight(1f),
                ) { Text("Wyzeruj") }
                Button(
                    onClick = { onSave(settings.clampedTo(durationMs), scope) },
                    modifier = Modifier.weight(1f),
                ) { Text("Zatwierdź") }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Przycina punkty do tego, co film faktycznie ma, i domyka drobiazgi, których
 * nikt nie chce ustawiać ręcznie: pętla bez własnego początku startuje tam, gdzie
 * pierwsze odtworzenie, a koniec przed początkiem znaczy „do końca filmu".
 *
 * To tutaj powstrzymujemy ustawienie punktu poza końcem filmu — bez tego
 * odtwarzacz przewijał na koniec, natychmiast wykrywał koniec segmentu i wpadał
 * w setki powtórzeń ostatniego ułamka sekundy.
 */
private fun LoopSettings.clampedTo(durationMs: Long): LoopSettings {
    if (durationMs <= 0L) return this // nie ma do czego przycinać; zrobi to odtwarzacz

    val ceiling = (durationMs - MIN_SEGMENT_MS).coerceAtLeast(0L)
    val start = startMs.coerceIn(0L, ceiling)
    var loopStart = loopStartMs.coerceIn(0L, ceiling)
    if (repeatMode != RepeatMode.OFF && loopStart == 0L && start > 0L) loopStart = start

    var end = endMs.coerceIn(0L, durationMs)
    if (end > 0L && end - maxOf(start, loopStart) < MIN_SEGMENT_MS) end = 0L
    if (end >= durationMs) end = 0L

    return copy(startMs = start, loopStartMs = loopStart, endMs = end)
}

private const val MIN_SEGMENT_MS = 600L

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun TimeSlider(
    label: String,
    valueMs: Long,
    maxMs: Float,
    currentPositionMs: Long,
    onChange: (Long) -> Unit,
    onListen: (Long) -> Unit,
    hint: String? = null,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                formatTime(valueMs),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = valueMs.toFloat().coerceIn(0f, maxMs),
            onValueChange = { onChange(it.toLong()) },
            valueRange = 0f..maxMs,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { onChange(currentPositionMs) },
                label = { Text("Weź ${formatTime(currentPositionMs)}") },
            )
            AssistChip(onClick = { onListen(valueMs) }, label = { Text("Posłuchaj") })
            AssistChip(
                onClick = { onChange((valueMs - 500).coerceAtLeast(0L)) },
                label = { Text("−0,5 s") },
            )
            AssistChip(onClick = { onChange(valueMs + 500) }, label = { Text("+0,5 s") })
        }
    }
}
