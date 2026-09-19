package com.loopa.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.loopa.app.LoopaApp
import com.loopa.app.data.PlaylistWithCount
import com.loopa.app.data.Track
import com.loopa.app.media.PlayableItem
import com.loopa.app.media.PlayerConnection
import com.loopa.app.resolve.LinkParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@UnstableApi
class LibraryViewModel(private val app: LoopaApp) : ViewModel() {

    private val repo = app.repository

    val playlists: StateFlow<List<PlaylistWithCount>> = repo.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val loose: StateFlow<List<Track>> = repo.observeUnfiled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun createPlaylist(name: String) = viewModelScope.launch {
        repo.createPlaylist(name)
    }

    fun deletePlaylist(id: Long) = viewModelScope.launch { repo.deletePlaylist(id) }

    /** Wklejenie linku recznie - ta sama sciezka co "Udostepnij". */
    fun addLink(text: String, onAdded: (Track) -> Unit = {}) = viewModelScope.launch {
        if (!LinkParser.isSupported(text)) {
            _message.value = "To nie wyglada na link do YouTube ani TikToka."
            return@launch
        }
        _busy.value = true
        val track = runCatching { repo.addFromSharedText(text) }.getOrNull()
        _busy.value = false
        if (track == null) {
            _message.value = "Nie udalo sie pobrac tego filmu."
        } else {
            onAdded(track)
        }
    }

    fun playTrack(track: Track) {
        PlayerConnection.playSingle(PlayableItem.of(track))
    }

    fun playAllLoose(startIndex: Int) {
        val items = loose.value.map { PlayableItem.of(it) }
        PlayerConnection.play(items, startIndex)
    }

    fun deleteTrack(track: Track) = viewModelScope.launch { repo.deleteTrack(track.id) }

    fun addToPlaylist(playlistId: Long, trackId: String) = viewModelScope.launch {
        val added = repo.addToPlaylist(playlistId, trackId)
        _message.value = if (added == null) "Ten film juz tam jest." else "Dodano do playlisty."
    }

    fun consumeMessage() { _message.value = null }
}
