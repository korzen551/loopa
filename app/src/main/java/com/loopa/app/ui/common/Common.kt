package com.loopa.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.loopa.app.data.LoopSettings
import com.loopa.app.data.RepeatMode
import com.loopa.app.data.Source
import java.util.Locale

fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes >= 60) {
        String.format(Locale.US, "%d:%02d:%02d", minutes / 60, minutes % 60, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/** Krotki opis pętli do listy, np. "pętla od 0:09". */
fun LoopSettings.describe(): String? {
    if (!isCustom) return null
    val parts = buildList {
        when (repeatMode) {
            RepeatMode.LOOP -> add("pętla od ${formatTime(loopStartMs)}")
            RepeatMode.COUNT -> add("${repeatCount}× od ${formatTime(loopStartMs)}")
            RepeatMode.OFF -> if (startMs > 0L) add("start ${formatTime(startMs)}")
        }
        if (endMs > 0L) add("do ${formatTime(endMs)}")
        if (repeatMode != RepeatMode.OFF && startMs != loopStartMs) {
            add("1. raz od ${formatTime(startMs)}")
        }
    }
    return parts.joinToString(" · ").ifBlank { null }
}

val Source.shortLabel: String
    get() = when (this) {
        Source.YOUTUBE -> "YT"
        Source.TIKTOK -> "TT"
    }

@Composable
fun Thumb(
    url: String?,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.BottomEnd,
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (badge != null) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .padding(4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
fun RowMeta(
    title: String,
    subtitle: String?,
    accent: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!accent.isNullOrBlank()) {
            Text(
                text = accent,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Ustawienie jako kwadrat z ptaszkiem. Gdy [enabled] jest false, caly wiersz
 * blednie i nie reaguje - tak wygladaja podustawienia przed wlaczeniem
 * przewijania, zeby bylo widac, ze istnieja, ale jeszcze nie dzialaja.
 */
@Composable
fun CheckRow(
    checked: Boolean,
    label: String,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    description: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onCheckedChange(it) },
            enabled = enabled,
        )
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                )
            }
        }
        if (trailing != null) trailing()
    }
}

/** Licznik "ile razy" - minus, liczba, plus. */
@Composable
fun CountStepper(
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    min: Int = 1,
    max: Int = 999,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { onChange((value - 1).coerceAtLeast(min)) },
            enabled = enabled && value > min,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) { Text("−") }
        Text(
            text = "$value",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
            modifier = Modifier.width(44.dp),
        )
        OutlinedButton(
            onClick = { onChange((value + 1).coerceAtMost(max)) },
            enabled = enabled && value < max,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) { Text("+") }
    }
}

@Composable
fun ListRow(
    thumbUrl: String?,
    badge: String?,
    title: String,
    subtitle: String?,
    accent: String?,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(thumbUrl, Modifier.size(width = 84.dp, height = 56.dp), badge)
        Spacer(Modifier.width(12.dp))
        RowMeta(title, subtitle, accent, Modifier.weight(1f))
        trailing()
    }
}
