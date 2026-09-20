package com.loopa.app.ui.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.loopa.app.LoopaApp
import com.loopa.app.data.Playlist
import com.loopa.app.data.PlaylistEntry
import com.loopa.app.download.DownloadJob
import com.loopa.app.download.DownloadState
import com.loopa.app.download.GalleryAlbum
import com.loopa.app.media.PlayableItem
import com.loopa.app.media.PlayerConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SavePlaylistState(
    val playlist: Playlist? = null,
    val entries: List<PlaylistEntry> = emptyList(),
)

/** Fragment filmu do zapisania. [endMs] rowne 0 znaczy "do konca". */
data class Trim(val startMs: Long = 0L, val endMs: Long = 0L) {
    val isWholeVideo: Boolean get() = startMs == 0L && endMs == 0L
}

/**
 * Zapis wielu filmow z playlisty naraz.
 *
 * Domyslnie zaznaczone jest wszystko i wszystko w calosci - "Zapisz playliste"
 * ma dzialac jednym kliknieciem. Dopiero "Wybierz filmy" odznacza wszystko
 * i oddaje wybor uzytkownikowi.
 */
@UnstableApi
class SavePlaylistViewModel(private val app: LoopaApp, playlistId: Long) : ViewModel() {

    private val repo = app.repository

    val state: StateFlow<SavePlaylistState> =
        combine(repo.observePlaylist(playlistId), repo.observeEntries(playlistId)) { playlist, entries ->
            SavePlaylistState(playlist, entries)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SavePlaylistState())

    /** Id wpisow zaznaczonych do zapisu. */
    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    /** Fragment do zapisania dla danego wpisu; brak wpisu = caly film. */
    private val _trims = MutableStateFlow<Map<Long, Trim>>(emptyMap())
    val trims: StateFlow<Map<Long, Trim>> = _trims.asStateFlow()

    private val _albums = MutableStateFlow<List<GalleryAlbum>>(emptyList())
    val albums: StateFlow<List<GalleryAlbum>> = _albums.asStateFlow()

    private val _albumsLoading = MutableStateFlow(false)
    val albumsLoading: StateFlow<Boolean> = _albumsLoading.asStateFlow()

    val downloadState: StateFlow<DownloadState> = app.downloads.state

    private var primedForEntries: List<Long> = emptyList()

    /** Pierwsze wejscie na ekran zaznacza cala playliste. */
    fun primeSelection() {
        val ids = state.value.entries.map { it.item.id }
        if (ids.isEmpty() || ids == primedForEntries) return
        primedForEntries = ids
        _selected.value = ids.toSet()
    }

    fun toggle(itemId: Long) {
        _selected.value = _selected.value.toMutableSet().apply {
            if (!add(itemId)) remove(itemId)
        }
    }

    fun selectAll() {
        _selected.value = state.value.entries.map { it.item.id }.toSet()
    }

    /** "Wybierz filmy" - odznacza wszystko, zeby uzytkownik wskazal sam. */
    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun setTrim(itemId: Long, startMs: Long, endMs: Long) {
        val trim = Trim(startMs, endMs)
        _trims.value = _trims.value.toMutableMap().apply {
            if (trim.isWholeVideo) remove(itemId) else put(itemId, trim)
        }
    }

    fun trimFor(itemId: Long): Trim = _trims.value[itemId] ?: Trim()

    fun loadAlbums() = viewModelScope.launch {
        if (_albums.value.isNotEmpty()) return@launch
        _albumsLoading.value = true
        _albums.value = runCatching { app.gallery.albums() }.getOrDefault(listOf(GalleryAlbum.Default))
        _albumsLoading.value = false
    }

    /** Podglad konkretnego filmu w arkuszu edycji - leci solo, poza playlista. */
    fun preview(entry: PlaylistEntry) {
        PlayerConnection.playSingle(PlayableItem.of(entry.track))
    }

    fun save(album: GalleryAlbum) {
        val chosen = _selected.value
        val jobs = state.value.entries
            .filter { it.item.id in chosen }
            .map { entry ->
                val trim = trimFor(entry.item.id)
                DownloadJob(
                    trackId = entry.track.id,
                    title = entry.track.title,
                    startMs = trim.startMs,
                    endMs = trim.endMs,
                    album = album,
                )
            }
        app.downloads.enqueue(jobs)
    }

    fun acknowledgeDownload() = app.downloads.acknowledge()
}
