package com.loopa.app.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.loopa.app.LoopaApp
import com.loopa.app.data.LoopSettings
import com.loopa.app.data.Source
import com.loopa.app.data.Track
import com.loopa.app.media.PlayableItem
import com.loopa.app.media.PlayerConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@UnstableApi
class PlayerViewModel(private val app: LoopaApp) : ViewModel() {

    private val repo = app.repository

    private val _track = MutableStateFlow<Track?>(null)
    val track: StateFlow<Track?> = _track.asStateFlow()

    private val _hasOverride = MutableStateFlow(false)
    val hasOverride: StateFlow<Boolean> = _hasOverride.asStateFlow()

    /** Gdy ekstrakcja strumienia padła, zostaje oficjalny embed w WebView. */
    private val _embedFallback = MutableStateFlow(false)
    val embedFallback: StateFlow<Boolean> = _embedFallback.asStateFlow()

    fun onNowPlayingChanged(trackId: String?, playlistItemId: Long?) = viewModelScope.launch {
        val loaded = trackId?.let { repo.track(it) }
        _track.value = loaded
        _hasOverride.value = playlistItemId?.let { repo.entry(it)?.item?.hasLoopOverride } ?: false
        _embedFallback.value = false
    }

    /** Odtwarzacz sam pozna długość dopiero po starcie — uzupełniamy ją wtedy w bazie. */
    fun rememberDuration(trackId: String?, durationMs: Long) = viewModelScope.launch {
        if (trackId != null) repo.fillDuration(trackId, durationMs)
    }

    fun previewLoop(settings: LoopSettings) {
        val current = _track.value ?: return
        PlayerConnection.updateCurrentLoop(
            PlayableItem(current, settings, playlistItemId = currentItemId)
        )
    }

    var currentItemId: Long? = null
        private set

    fun bindItemId(id: Long?) { currentItemId = id }

    fun saveLoop(settings: LoopSettings, scope: LoopScope) = viewModelScope.launch {
        val current = _track.value ?: return@launch
        when (scope) {
            LoopScope.TRACK -> repo.saveTrackLoop(current.id, settings)
            LoopScope.ITEM -> currentItemId?.let { repo.saveItemLoop(it, settings) }
                ?: repo.saveTrackLoop(current.id, settings)
        }
        _track.value = repo.track(current.id)
        _hasOverride.value = currentItemId?.let { repo.entry(it)?.item?.hasLoopOverride } ?: false

        // Zatwierdzenie ma byc slychac od razu, a nie dopiero przy kolejnym
        // powtorzeniu: podmieniamy ustawienia i przestawiamy film na punkt startu.
        previewLoop(settings)
        PlayerConnection.restartCurrentLoop()
    }

    fun clearOverride() = viewModelScope.launch {
        val id = currentItemId ?: return@launch
        repo.clearItemLoop(id)
        _hasOverride.value = false
        _track.value?.let { PlayerConnection.updateCurrentLoop(PlayableItem(it, it.loop, id)) }
    }

    fun useEmbedFallback() {
        _embedFallback.value = true
    }

    fun retryStream() = viewModelScope.launch {
        val current = _track.value ?: return@launch
        app.resolvers.invalidate(current.id)
        _embedFallback.value = false
        PlayerConnection.playSingle(PlayableItem(current, current.loop, currentItemId))
    }

    /** Adres oficjalnego odtwarzacza osadzonego — awaryjnie, gdy strumień nie wyszedł. */
    fun embedUrl(): String? {
        val current = _track.value ?: return null
        val startSeconds = (current.loop.startMs / 1000).coerceAtLeast(0L)
        return when (current.source) {
            Source.YOUTUBE ->
                "https://www.youtube.com/embed/${current.sourceId}" +
                    "?autoplay=1&playsinline=1&rel=0&start=$startSeconds"
            Source.TIKTOK ->
                "https://www.tiktok.com/embed/v2/${current.sourceId}"
        }
    }
}
