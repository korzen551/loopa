package com.loopa.app.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.loopa.app.LoopaApp
import com.loopa.app.data.LoopSettings
import com.loopa.app.data.Playlist
import com.loopa.app.data.PlaylistEntry
import com.loopa.app.media.PlayableItem
import com.loopa.app.media.PlayerConnection
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@UnstableApi
class PlaylistViewModel(app: LoopaApp, private val playlistId: Long) : ViewModel() {

    private val repo = app.repository

    val playlist: StateFlow<Playlist?> = repo.observePlaylist(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val entries: StateFlow<List<PlaylistEntry>> = repo.observeEntries(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun playFrom(index: Int) {
        val items = entries.value.map { PlayableItem.of(it) }
        PlayerConnection.play(items, index)
    }

    fun rename(name: String) = viewModelScope.launch { repo.renamePlaylist(playlistId, name) }

    fun remove(itemId: Long) = viewModelScope.launch { repo.removeItem(itemId) }

    fun move(from: Int, to: Int) = viewModelScope.launch { repo.move(playlistId, from, to) }

    /** Zapis pętli tylko dla tego wpisu - ten sam film w innej playliście zostaje bez zmian. */
    fun saveItemLoop(itemId: Long, loop: LoopSettings) = viewModelScope.launch {
        repo.saveItemLoop(itemId, loop)
    }

    /** Zapis pętli jako domyślnej dla filmu - działa wszędzie, gdzie nie ma nadpisania. */
    fun saveTrackLoop(trackId: String, loop: LoopSettings) = viewModelScope.launch {
        repo.saveTrackLoop(trackId, loop)
    }

    fun clearItemLoop(itemId: Long) = viewModelScope.launch { repo.clearItemLoop(itemId) }
}
