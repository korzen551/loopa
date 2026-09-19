package com.loopa.app.ui.feed

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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FeedState(
    val playlist: Playlist? = null,
    val entries: List<PlaylistEntry> = emptyList(),
)

@UnstableApi
class FeedViewModel(private val app: LoopaApp, private val playlistId: Long) : ViewModel() {

    private val repo = app.repository

    val state: StateFlow<FeedState> =
        combine(repo.observePlaylist(playlistId), repo.observeEntries(playlistId)) { playlist, entries ->
            FeedState(playlist, entries)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedState())

    /** Czy kolejka w serwisie odpowiada juz tej playliscie. */
    private var queueLoadedFor: List<Long> = emptyList()

    /**
     * Wrzuca playliste do odtwarzacza. Ustawienia przewijania sa doliczane tutaj,
     * wiec zmiana w menu od razu przeklada sie na to, co robi odtwarzacz.
     */
    fun ensureQueue(startIndex: Int, force: Boolean = false) {
        val current = state.value
        if (current.entries.isEmpty()) return
        val signature = current.entries.map { it.item.id }
        if (!force && signature == queueLoadedFor) return
        queueLoadedFor = signature

        val items = current.entries.map { entry ->
            PlayableItem(
                track = entry.track,
                loop = effectiveLoop(current.playlist, entry),
                playlistItemId = entry.item.id,
            )
        }
        PlayerConnection.play(items, startIndex)
    }

    /** Przeladowuje kolejke w miejscu, zachowujac pozycje - po zmianie ustawien. */
    fun reloadQueue(currentIndex: Int) = ensureQueue(currentIndex, force = true)

    fun goTo(index: Int) {
        val entry = state.value.entries.getOrNull(index) ?: return
        PlayerConnection.goToIndex(index, effectiveLoop(state.value.playlist, entry).startMs)
    }

    fun savePlaylistSettings(playlist: Playlist, currentIndex: Int) = viewModelScope.launch {
        repo.savePlaylistSettings(playlist)
        reloadQueue(currentIndex)
    }

    fun setItemPlayCount(itemId: Long, count: Int, currentIndex: Int) = viewModelScope.launch {
        repo.setItemPlayCount(itemId, count)
        reloadQueue(currentIndex)
    }

    fun saveLoopPoints(entry: PlaylistEntry, points: LoopSettings, currentIndex: Int) =
        viewModelScope.launch {
            repo.saveItemLoop(entry.item.id, points)
            reloadQueue(currentIndex)
            PlayerConnection.restartCurrentLoop()
        }

    /**
     * Wyrzuca zapamietany adres strumienia i probuje od nowa. Podpisane adresy
     * googlevideo wygasaja, wiec ponowna ekstrakcja czesto wystarcza.
     */
    fun retryCurrent(currentIndex: Int) = viewModelScope.launch {
        val entry = state.value.entries.getOrNull(currentIndex) ?: return@launch
        app.resolvers.invalidate(entry.track.id)
        reloadQueue(currentIndex)
    }

    fun clearLoopOverride(itemId: Long, currentIndex: Int) = viewModelScope.launch {
        repo.clearItemLoop(itemId)
        reloadQueue(currentIndex)
        PlayerConnection.restartCurrentLoop()
    }

    fun rememberDuration(trackId: String?, durationMs: Long) = viewModelScope.launch {
        if (trackId != null) repo.fillDuration(trackId, durationMs)
    }
}
