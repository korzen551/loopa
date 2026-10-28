package com.loopa.app.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.loopa.app.LoopaApp
import com.loopa.app.data.LoopSettings
import com.loopa.app.data.Playlist
import com.loopa.app.data.PlaylistEntry
import com.loopa.app.data.effectiveLoop
import com.loopa.app.media.PlayableItem
import com.loopa.app.media.PlayerConnection
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
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

    init {
        viewModelScope.launch {
            repo.observeEntries(playlistId).collect { list -> repo.refreshStale(list.map { it.track }) }
        }
    }

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
        applyToNowPlaying()
    }

    /** Zapis pętli jako domyślnej dla filmu - działa wszędzie, gdzie nie ma nadpisania. */
    fun saveTrackLoop(trackId: String, loop: LoopSettings) = viewModelScope.launch {
        repo.saveTrackLoop(trackId, loop)
        applyToNowPlaying()
    }

    fun clearItemLoop(itemId: Long) = viewModelScope.launch {
        repo.clearItemLoop(itemId)
        applyToNowPlaying()
    }

    /**
     * Gdy zmieniony wpis wlasnie gra, nowe ustawienia maja zadzialac od razu, a nie
     * dopiero przy nastepnym wczytaniu playlisty. Czytamy wpis prosto z bazy - po
     * zapisie to on ma juz nowe wartosci.
     */
    private suspend fun applyToNowPlaying() {
        val itemId = PlayerConnection.currentPlaylistItemId() ?: return
        val entry = repo.entry(itemId)?.takeIf { it.item.playlistId == playlistId } ?: return
        val playlist = repo.observePlaylist(playlistId).first()
        PlayerConnection.updateCurrentLoop(PlayableItem(entry.track, effectiveLoop(playlist, entry), itemId))
        PlayerConnection.restartCurrentLoop()
    }
}
