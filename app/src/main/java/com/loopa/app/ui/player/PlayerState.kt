package com.loopa.app.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.loopa.app.data.LoopSettings
import com.loopa.app.media.PlayerConnection
import com.loopa.app.media.loopSettings
import com.loopa.app.media.playlistItemId
import kotlinx.coroutines.delay

data class PlayerUiState(
    val controller: MediaController? = null,
    val hasMedia: Boolean = false,
    val isPlaying: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val artworkUrl: String? = null,
    val trackId: String? = null,
    val playlistItemId: Long? = null,
    /** Pozycja w kolejce; feed synchronizuje po niej przewijanie. -1 gdy nic nie gra. */
    val queueIndex: Int = -1,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val loop: LoopSettings = LoopSettings.Default,
    val error: String? = null,
)

/**
 * Jedno zrodlo prawdy o tym, co teraz leci. Czyta sesje odtwarzania, wiec pokazuje
 * to samo, co powiadomienie i ekran blokady - nawet jesli ekran wlasnie wrocil.
 */
@UnstableApi
@Composable
fun rememberPlayerState(): PlayerUiState {
    val controller by PlayerConnection.controller.collectAsState()
    var snapshot by remember { mutableStateOf(PlayerUiState()) }

    DisposableEffect(controller) {
        val player = controller
        if (player == null) {
            snapshot = PlayerUiState()
            return@DisposableEffect onDispose { }
        }

        fun read(error: String? = snapshot.error) {
            val item: MediaItem? = player.currentMediaItem
            snapshot = PlayerUiState(
                controller = player,
                hasMedia = player.mediaItemCount > 0,
                isPlaying = player.isPlaying,
                title = item?.mediaMetadata?.title?.toString().orEmpty(),
                artist = item?.mediaMetadata?.artist?.toString().orEmpty(),
                artworkUrl = item?.mediaMetadata?.artworkUri?.toString(),
                trackId = item?.mediaId,
                playlistItemId = item.playlistItemId(),
                queueIndex = if (player.mediaItemCount > 0) player.currentMediaItemIndex else -1,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L,
                loop = item.loopSettings(),
                error = error,
            )
        }

        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) = read()
            override fun onPlayerError(e: PlaybackException) = read(error = friendlyError(e))
        }
        player.addListener(listener)
        read()
        onDispose { player.removeListener(listener) }
    }

    // Pozycja nie ma wlasnych zdarzen - trzeba ja odpytywac. Tylko gdy cos gra
    // i tylko dopoki ten ekran jest w kompozycji.
    LaunchedEffect(controller, snapshot.isPlaying) {
        val player = controller ?: return@LaunchedEffect
        if (!snapshot.isPlaying) return@LaunchedEffect
        while (true) {
            snapshot = snapshot.copy(
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: snapshot.durationMs,
            )
            delay(200)
        }
    }

    return snapshot
}

private fun friendlyError(e: PlaybackException): String = when {
    e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
        "Brak polaczenia z siecia."
    e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        "Zrodlo odrzucilo odtwarzanie - sprobuj odswiezyc film."
    else -> "Nie udalo sie odtworzyc tego filmu."
}
