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
import com.loopa.app.resolve.SpotifyKind
import com.loopa.app.resolve.SpotifyLink
import com.loopa.app.resolve.SpotifyLinks
import com.loopa.app.resolve.SpotifySong
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ShareState {
    data class Loading(val message: String = "Pobieram dane filmu…") : ShareState

    /** Jeden film gotowy do przypisania do playlist. [note] mowi, skad sie wzial. */
    data class Ready(val track: Track, val note: String? = null) : ShareState

    /** Playlista albo album ze Spotify w trakcie dopasowywania do YouTube'a. */
    data class Importing(
        val name: String,
        val coverUrl: String?,
        val total: Int,
        val done: Int = 0,
        val found: Int = 0,
    ) : ShareState

    data class CollectionReady(val import: CollectionImport) : ShareState

    data class Failed(val reason: String) : ShareState
}

/** Wynik dopasowania calej playlisty albo albumu - jeszcze niezapisany. */
data class CollectionImport(
    val name: String,
    val kind: SpotifyKind,
    val owner: String?,
    val coverUrl: String?,
    /** Znalezione filmy w kolejnosci ze Spotify, bez powtorzen. */
    val tracks: List<Track>,
    val missing: List<SpotifySong>,
    val total: Int,
    val mayBeTruncated: Boolean,
)

@UnstableApi
class ShareViewModel(app: LoopaApp) : ViewModel() {

    private val repo = app.repository

    private val _state = MutableStateFlow<ShareState>(ShareState.Loading())
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

    /**
     * Przy playliscie ze Spotify domyslnie powstaje nowa playlista w Loopa o tej
     * samej nazwie - tak, jak ktos, kto udostepnia cala playliste, zwykle chce.
     */
    private val _createNew = MutableStateFlow(false)
    val createNew: StateFlow<Boolean> = _createNew.asStateFlow()

    private val _newName = MutableStateFlow("")
    val newName: StateFlow<String> = _newName.asStateFlow()

    private var accepted = false
    private var acceptedText: String? = null
    private var work: Job? = null

    fun accept(sharedText: String?) {
        // Obrot ekranu odpala to jeszcze raz z tym samym tekstem - dopasowanie
        // setki utworow nie powinno przez to ruszac od nowa.
        if (accepted && sharedText == acceptedText && _state.value !is ShareState.Failed) return
        accepted = true
        acceptedText = sharedText
        work?.cancel()
        _selected.value = emptySet()
        _alreadyIn.value = emptySet()
        _createNew.value = false

        work = viewModelScope.launch {
            when {
                LinkParser.isSupported(sharedText) -> acceptVideo(sharedText!!)
                SpotifyLinks.mentions(sharedText) -> acceptSpotify(sharedText!!)
                else -> _state.value = ShareState.Failed("Nie znalazłem tu linku do YouTube, TikToka ani Spotify.")
            }
        }
    }

    private suspend fun acceptVideo(text: String) {
        _state.value = ShareState.Loading()
        val track = runCatching { repo.addFromSharedText(text) }.getOrNull()
        _state.value = track?.let { ShareState.Ready(it) }
            ?: ShareState.Failed("Nie udało się pobrać danych tego filmu.")
        showWhereAlready(track)
    }

    private suspend fun acceptSpotify(text: String) {
        _state.value = ShareState.Loading("Czytam link ze Spotify…")
        val link = repo.resolveSpotify(text)
        if (link == null) {
            _state.value = ShareState.Failed("To nie wygląda na utwór, album ani playlistę ze Spotify.")
            return
        }
        when (link.kind) {
            SpotifyKind.TRACK -> acceptSpotifySong(link)
            SpotifyKind.PLAYLIST, SpotifyKind.ALBUM -> acceptSpotifyCollection(link)
        }
    }

    private suspend fun acceptSpotifySong(link: SpotifyLink) {
        _state.value = ShareState.Loading("Szukam tego utworu na YouTube…")
        val result = runCatching { repo.addSpotifySong(link) }
        val added = result.getOrElse {
            _state.value = ShareState.Failed(it.message ?: "Nie udało się odczytać utworu ze Spotify.")
            return
        }
        if (added == null) {
            _state.value = ShareState.Failed(
                "Nie znalazłem tego utworu na YouTube. Spotify nie udostępnia dźwięku poza swoją " +
                    "apką, więc Loopa gra go z YouTube'a — a tam nie ma nic wystarczająco podobnego."
            )
            return
        }
        _state.value = ShareState.Ready(
            track = added.track,
            note = "Ze Spotify · na YouTube: ${added.match.videoTitle}",
        )
        showWhereAlready(added.track)
    }

    private suspend fun acceptSpotifyCollection(link: SpotifyLink) {
        _state.value = ShareState.Loading(
            if (link.kind == SpotifyKind.ALBUM) "Czytam album ze Spotify…" else "Czytam playlistę ze Spotify…"
        )
        val collection = runCatching { repo.spotifyCollection(link) }.getOrElse {
            _state.value = ShareState.Failed(it.message ?: "Nie udało się odczytać tej playlisty ze Spotify.")
            return
        }
        if (collection.songs.isEmpty()) {
            _state.value = ShareState.Failed("Na tej playliście nie ma żadnych utworów.")
            return
        }

        _newName.value = collection.name
        _createNew.value = true
        _state.value = ShareState.Importing(collection.name, collection.coverUrl, collection.songs.size)

        val matched = repo.matchSongs(collection.songs) { found ->
            // Wolane rownolegle z kilku watkow - update() liczy bez gubienia krokow.
            _state.update { current ->
                if (current is ShareState.Importing) {
                    current.copy(done = current.done + 1, found = current.found + if (found) 1 else 0)
                } else {
                    current
                }
            }
        }

        val tracks = matched.filterNotNull().distinctBy { it.id }
        if (tracks.isEmpty()) {
            _state.value = ShareState.Failed("Nie znalazłem na YouTube żadnego z tych utworów.")
            return
        }
        _state.value = ShareState.CollectionReady(
            CollectionImport(
                name = collection.name,
                kind = collection.link.kind,
                owner = collection.owner,
                coverUrl = collection.coverUrl,
                tracks = tracks,
                missing = collection.songs.filterIndexed { index, _ -> matched[index] == null },
                total = collection.songs.size,
                mayBeTruncated = collection.mayBeTruncated,
            )
        )
    }

    private suspend fun showWhereAlready(track: Track?) {
        // Pokaż od razu, gdzie ten klip już leży - żeby nie dokładać go drugi raz.
        _alreadyIn.value = track?.let { repo.playlistIdsFor(it.id) }?.toSet().orEmpty()
    }

    fun toggle(playlistId: Long) {
        _selected.value = _selected.value.toMutableSet().apply {
            if (!add(playlistId)) remove(playlistId)
        }
    }

    fun setCreateNew(enabled: Boolean) {
        _createNew.value = enabled
    }

    fun renameNew(name: String) {
        _newName.value = name.trim()
        _createNew.value = true
    }

    fun createPlaylist(name: String) = viewModelScope.launch {
        val id = repo.createPlaylist(name)
        _selected.value = _selected.value + id
    }

    /** Zapisuje przypisania i oddaje film, żeby dało się go od razu odtworzyć. */
    fun commit(onDone: (Track?) -> Unit) {
        // Ustawiane od razu, a nie w korutynie - drugie szybkie tapniecie "Zapisz"
        // utworzyloby inaczej druga taka sama playliste.
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                when (val current = _state.value) {
                    is ShareState.Ready -> {
                        _selected.value.forEach { playlistId ->
                            repo.addToPlaylist(playlistId, current.track.id)
                        }
                        onDone(current.track)
                    }

                    is ShareState.CollectionReady -> {
                        val ids = repo.saveTracks(current.import.tracks).map { it.id }
                        val targets = _selected.value.toMutableList()
                        if (_createNew.value) {
                            targets += repo.createPlaylist(_newName.value.ifBlank { current.import.name })
                        }
                        targets.forEach { playlistId -> repo.addAllToPlaylist(playlistId, ids) }
                        onDone(null)
                    }

                    else -> onDone(null)
                }
            } finally {
                _saving.value = false
            }
        }
    }

    fun playNow() {
        val ready = _state.value as? ShareState.Ready ?: return
        PlayerConnection.playSingle(PlayableItem.of(ready.track))
    }
}
