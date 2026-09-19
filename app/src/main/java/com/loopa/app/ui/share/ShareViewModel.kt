package com.loopa.app.ui.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.loopa.app.LoopaApp
import com.loopa.app.data.Playlist
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

sealed interface ShareState {
    data object Loading : ShareState
    data class Ready(val track: Track) : ShareState
    data class Failed(val reason: String) : ShareState
}

@UnstableApi
class ShareViewModel(app: LoopaApp) : ViewModel() {

    private val repo = app.repository

    private val _state = MutableStateFlow<ShareState>(ShareState.Loading)
    val state: StateFlow<ShareState> = _state.asStateFlow()

    val playlists: StateFlow<List<Playlist>> = repo.observePlaylistsRaw()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    /** Playlisty, w których ten film już jest - pokazujemy je jako odhaczone i zablokowane. */
    private val _alreadyIn = MutableStateFlow<Set<Long>>(emptySet())
    val alreadyIn: StateFlow<Set<Long>> = _alreadyIn.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    fun accept(sharedText: String?) = viewModelScope.launch {
        if (!LinkParser.isSupported(sharedText)) {
            _state.value = ShareState.Failed("Nie znalazłem tu linku do YouTube ani TikToka.")
            return@launch
        }
        _state.value = ShareState.Loading
        val track = runCatching { repo.addFromSharedText(sharedText!!) }.getOrNull()
        _state.value = track?.let { ShareState.Ready(it) }
            ?: ShareState.Failed("Nie udało się pobrać danych tego filmu.")

        // Pokaż od razu, gdzie ten klip już leży - żeby nie dokładać go drugi raz.
        _alreadyIn.value = track?.let { repo.playlistIdsFor(it.id) }?.toSet().orEmpty()
    }

    fun toggle(playlistId: Long) {
        _selected.value = _selected.value.toMutableSet().apply {
            if (!add(playlistId)) remove(playlistId)
        }
    }

    fun createPlaylist(name: String) = viewModelScope.launch {
        val id = repo.createPlaylist(name)
        _selected.value = _selected.value + id
    }

    /** Zapisuje przypisania i oddaje film, żeby dało się go od razu odtworzyć. */
    fun commit(onDone: (Track?) -> Unit) = viewModelScope.launch {
        val ready = _state.value as? ShareState.Ready
        if (ready == null) {
            onDone(null)
            return@launch
        }
        _saving.value = true
        _selected.value.forEach { playlistId ->
            repo.addToPlaylist(playlistId, ready.track.id)
        }
        _saving.value = false
        onDone(ready.track)
    }

    fun playNow() {
        val ready = _state.value as? ShareState.Ready ?: return
        PlayerConnection.playSingle(PlayableItem.of(ready.track))
    }
}
