package com.loopa.app.ui.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.loopa.app.ui.common.formatTime
import java.util.Locale

/**
 * Czyta czas wpisany recznie. Podstawowa jednostka to SEKUNDY, a kazda kolejna
 * kropka przesuwa znaczenie o jeden rzad w gore:
 *
 *   "4"        -> 4 sekundy
 *   "4.51"     -> 4 minuty 51 sekund
 *   "4.51.20"  -> 4 godziny 51 minut 20 sekund
 *
 * Czyli liczby czytamy od prawej: ostatnia to sekundy, przed nia minuty,
 * przed nimi godziny.
 */
fun parseDurationInput(raw: String): Long? {
    val parts = raw.trim().split('.', ':', ',').map { it.trim() }
    if (parts.isEmpty() || parts.size > 3) return null
    if (parts.any { it.isEmpty() }) return null

    val numbers = parts.map { it.toIntOrNull() ?: return null }
    if (numbers.any { it < 0 }) return null

    val seconds = when (numbers.size) {
        1 -> numbers[0].toLong()
        2 -> numbers[0] * 60L + numbers[1]
        else -> numbers[0] * 3600L + numbers[1] * 60L + numbers[2]
    }
    return seconds * 1000L
}

/**
 * Odwrotnosc [parseDurationInput] - pokazuje najkrotszy zapis, ktory da sie
 * wpisac z powrotem. 40 sekund to "40", a nie mylace "0.40".
 */
fun formatDurationInput(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return when {
        h > 0 -> String.format(Locale.US, "%d.%02d.%02d", h, m, s)
        m > 0 -> String.format(Locale.US, "%d.%02d", m, s)
        else -> s.toString()
    }
}

/**
 * Wybor fragmentu filmu do zapisania - dwa ciecia, od poczatku i od konca.
 *
 * [endMs] rowne 0 znaczy "do samego konca filmu".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimControl(
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    onChange: (startMs: Long, endMs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val known = durationMs > 0L
    val effectiveEnd = if (endMs > 0L) endMs else durationMs

    // KLUCZOWE: pola tekstowe nie moga byc zerowane przy kazdej zmianie wartosci,
    // bo wtedy kasuja to, co wlasnie wpisujesz. Dlatego kluczem jest wylacznie
    // dlugosc filmu (stala dla danego klipu), a nie biezace ciecia. Suwak
    // aktualizuje teksty jawnie, wiec i tak zostaja zgodne.
    var startText by remember(durationMs) { mutableStateOf(formatDurationInput(startMs)) }
    var endText by remember(durationMs) { mutableStateOf(formatDurationInput(effectiveEnd)) }
    var startError by remember { mutableStateOf(false) }
    var endError by remember { mutableStateOf(false) }

    fun push(newStart: Long, newEnd: Long) {
        val safeStart = newStart.coerceIn(0L, (durationMs - 1000L).coerceAtLeast(0L))
        val safeEnd = newEnd.coerceIn(safeStart + 1000L, durationMs)
        onChange(safeStart, if (safeEnd >= durationMs) 0L else safeEnd)
    }

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Fragment do zapisania",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatTime((effectiveEnd - startMs).coerceAtLeast(0L)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (!known) {
            Text(
                "Długość tego filmu jest jeszcze nieznana — zapisze się w całości.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            return@Column
        }

        RangeSlider(
            value = startMs.toFloat()..effectiveEnd.toFloat().coerceAtLeast(startMs.toFloat() + 1000f),
            onValueChange = { range ->
                startError = false
                endError = false
                val newStart = range.start.toLong()
                val newEnd = range.endInclusive.toLong()
                startText = formatDurationInput(newStart)
                endText = formatDurationInput(newEnd)
                push(newStart, newEnd)
            },
            valueRange = 0f..durationMs.toFloat(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = startText,
                onValueChange = { text ->
                    startText = text
                    val parsed = parseDurationInput(text)
                    if (parsed == null) {
                        startError = text.isNotBlank()
                    } else {
                        startError = false
                        push(parsed, effectiveEnd)
                    }
                },
                label = { Text("Od") },
                isError = startError,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = endText,
                onValueChange = { text ->
                    endText = text
                    val parsed = parseDurationInput(text)
                    if (parsed == null) {
                        endError = text.isNotBlank()
                    } else {
                        endError = false
                        push(startMs, parsed)
                    }
                },
                label = { Text("Do") },
                isError = endError,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(6.dp))
        AssistChip(
            onClick = {
                startError = false
                endError = false
                startText = formatDurationInput(0L)
                endText = formatDurationInput(durationMs)
                onChange(0L, 0L)
            },
            label = { Text("Cały film") },
        )

        Spacer(Modifier.height(6.dp))
        Text(
            text = if (startError || endError) {
                "Nie rozumiem tego zapisu. Sekundy: 40. Minuty i sekundy: 4.51. " +
                    "Godziny, minuty i sekundy: 1.20.05."
            } else {
                "Z filmu o długości ${formatTime(durationMs)} zapiszę fragment od " +
                    "${formatTime(startMs)} do ${formatTime(effectiveEnd)}."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (startError || endError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
