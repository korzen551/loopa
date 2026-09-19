package com.loopa.app.ui.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

private val PRESETS = listOf(0.5f, 1f, 2f, 3f, 5f, 7f, 10f)

/**
 * Suwak predkosci - od 0,1x do 10x, plus szybkie przyciski na typowe wartosci.
 *
 * W przeciwienstwie do edytora petli, tutaj zmiana dziala na zywo przy kazdym
 * przesunieciu: to prosty mnoznik bez zadnych punktow do przycinania, wiec nie
 * ma tu ryzyka takiego bledu jak przy petli. Predkosc nie jest zapisywana -
 * wraca do 1x przy nastepnym filmie, tak jak w wiekszosci odtwarzaczy wideo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedSheet(
    initialSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var speed by remember { mutableFloatStateOf(initialSpeed) }

    fun apply(value: Float) {
        speed = value
        onSpeedChange(value)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text("Szybkość odtwarzania", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Nie zapisuje się na stałe - wraca do normalnej przy następnym filmie.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            Text(
                text = formatSpeed(speed),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Slider(
                value = speed,
                onValueChange = { apply(it) },
                valueRange = 0.1f..10f,
            )

            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(PRESETS) { preset ->
                    FilterChip(
                        selected = speed == preset,
                        onClick = { apply(preset) },
                        label = { Text(formatSpeed(preset)) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { apply(1f) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Wróć do normalnej (1x)")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) {
        "${speed.toInt()}x"
    } else {
        String.format(Locale.US, "%.1fx", speed)
    }
