package com.loopa.app.ui.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
 * Czyta czas wpisany recznie. Czyta od prawej: ostatnia liczba to sekundy,
 * przed nia minuty, przed nimi godziny. Kropka i dwukropek znacza to samo.
 *
 * "30.21"     -> 30 min 21 s
 * "1.20.30"   -> 1 h 20 min 30 s
 * "45"        -> 45 s
 */
fun parseDurationInput(raw: String): Long? {
    val parts = raw.trim().split('.', ':', ',').filter { it.isNotBlank() }
    if (parts.isEmpty() || parts.size > 3) return null
    val numbers = parts.map { it.trim().toIntOrNull() ?: return null }
    if (numbers.any { it < 0 }) return null

    val seconds = when (numbers.size) {
        1 -> numbers[0].toLong()
        2 -> numbers[0] * 60L + numbers[1]
        else -> numbers[0] * 3600L + numbers[1] * 60L + numbers[2]
    }
    return seconds * 1000L
}

/** Odwrotnosc [parseDurationInput] - do wypelnienia pola po ruchu suwakiem. */
fun formatDurationInput(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        String.format(Locale.US, "%d.%02d.%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d.%02d", m, s)
    }
}

/**
 * Wybor, ile z filmu zapisac: suwak plus pole do wpisania dokladnej wartosci.
 * Obie kontrolki pokazuja to samo i nawzajem sie aktualizuja.
 *
 * [limitMs] rowne 0 oznacza caly film.
 */
@Composable
fun TrimControl(
    durationMs: Long,
    limitMs: Long,
    onLimitChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val known = durationMs > 0L
    val effective = if (limitMs > 0L) limitMs else durationMs
    var typed by remember(limitMs, durationMs) { mutableStateOf(formatDurationInput(effective)) }
    var error by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Zapisz pierwsze",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatTime(effective),
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

        Slider(
            value = effective.toFloat().coerceIn(0f, durationMs.toFloat()),
            onValueChange = {
                error = false
                val value = it.toLong().coerceIn(1000L, durationMs)
                typed = formatDurationInput(value)
                onLimitChange(if (value >= durationMs) 0L else value)
            },
            valueRange = 0f..durationMs.toFloat(),
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = typed,
                onValueChange = { text ->
                    typed = text
                    val parsed = parseDurationInput(text)
                    if (parsed == null || parsed <= 0L) {
                        error = text.isNotBlank()
                    } else {
                        error = false
                        val clamped = parsed.coerceAtMost(durationMs)
                        onLimitChange(if (clamped >= durationMs) 0L else clamped)
                    }
                },
                label = { Text("min.sek") },
                isError = error,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(140.dp),
            )
            AssistChip(
                onClick = {
                    error = false
                    typed = formatDurationInput(durationMs)
                    onLimitChange(0L)
                },
                label = { Text("Całość") },
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = if (error) {
                "Nie rozumiem tego zapisu. Przykład: 30.21 to 30 minut i 21 sekund."
            } else {
                "Z filmu o długości ${formatTime(durationMs)} zapiszę pierwsze ${formatTime(effective)}."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
