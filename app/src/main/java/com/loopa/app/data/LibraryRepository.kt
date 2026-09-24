package com.loopa.app.data

import androidx.room.withTransaction
import com.loopa.app.resolve.LinkParser
import com.loopa.app.resolve.ResolverRegistry
import com.loopa.app.resolve.SpotifyClient
import com.loopa.app.resolve.SpotifyCollection
import com.loopa.app.resolve.SpotifyLink
import com.loopa.app.resolve.SpotifySong
import com.loopa.app.resolve.YouTubeMatch
import com.loopa.app.resolve.YouTubeMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Utwor ze Spotify zapisany jako film z YouTube'a, plus to, co dokladnie znalezlismy. */
data class SpotifyAdded(val track: Track, val match: YouTubeMatch)

/**
 * Jedyne miejsce, ktore dotyka bazy. Trzyma tylko metadane - zadnych plikow wideo
 * na telefonie; film jest za kazdym razem streamowany z sieci.
 */
class LibraryRepository(
    private val db: AppDatabase,
    private val resolvers: ResolverRegistry,
) {
    private val tracks = db.trackDao()
    private val playlists = db.playlistDao()
    private val items = db.playlistItemDao()

    private val spotify = SpotifyClient()
    private val matcher = YouTubeMatcher()

    // ---------- odczyt ----------

    fun observePlaylists(): Flow<List<PlaylistWithCount>> = playlists.observeAllWithCounts()
    fun observePlaylistsRaw(): Flow<List<Playlist>> = playlists.observeAll()
    fun observePlaylist(id: Long): Flow<Playlist?> = playlists.observe(id)
    fun observeEntries(playlistId: Long): Flow<List<PlaylistEntry>> = items.observeEntries(playlistId)
    fun observeAllTracks(): Flow<List<Track>> = tracks.observeAll()
    fun observeUnfiled(): Flow<List<Track>> = tracks.observeUnfiled()
    fun observeTrack(id: String): Flow<Track?> = tracks.observe(id)

    suspend fun track(id: String): Track? = tracks.byId(id)
    suspend fun allTracksOnce(): List<Track> = tracks.all()
    suspend fun downloadedTracks(): List<Track> = tracks.downloaded()

    // ---------- kopie offline ----------

    /** Zapamietuje, gdzie w galerii wyladowal pobrany plik i ile z filmu obejmuje. */
    suspend fun setLocalCopy(trackId: String, uri: String?, durationMs: Long) =
        tracks.setLocalCopy(trackId, uri, durationMs)

    /** Film zniknal z galerii (uzytkownik go skasowal) - wracamy do streamowania. */
    suspend fun forgetLocalCopy(trackId: String) = tracks.setLocalCopy(trackId, null, 0L)
    suspend fun entries(playlistId: Long): List<PlaylistEntry> = items.entries(playlistId)
    suspend fun entry(itemId: Long): PlaylistEntry? = items.entry(itemId)
    suspend fun playlistIdsFor(trackId: String): List<Long> = items.playlistIdsFor(trackId)

    // ---------- dodawanie ----------

    /**
     * Bierze surowy tekst z "Udostepnij" (moze zawierac opis + link), wyciaga link,
     * pobiera metadane i zapisuje film. Zwraca gotowy [Track] albo null przy blednym linku.
     */
    suspend fun addFromSharedText(text: String): Track? {
        val parsed = LinkParser.parse(text) ?: return null

        // Krotki link TikToka (vm.tiktok.com/...) nie niesie prawdziwego id filmu.
        // Rozwijamy go teraz, zeby ten sam klip udostepniony dwa razy nie zrobil
        // dwoch osobnych wpisow w bibliotece.
        val upgrade = if (parsed.needsRedirectResolve) resolvers.metadata(parsed) else null
        val link = upgrade?.sourceId?.let { realId ->
            parsed.copy(
                sourceId = realId,
                canonicalUrl = upgrade.canonicalUrl ?: parsed.canonicalUrl,
                needsRedirectResolve = false,
            )
        } ?: parsed

        val existing = tracks.byId(link.trackId)
        if (existing != null) {
            // Znany film - odswiez tylko czas startu z parametru ?t=, jesli byl.
            if (link.startMs > 0 && !existing.loop.isCustom) {
                saveTrackLoop(existing.id, existing.loop.copy(startMs = link.startMs, loopStartMs = link.startMs))
                return tracks.byId(existing.id)
            }
            return existing
        }

        val meta = upgrade ?: resolvers.metadata(link)
        val track = Track(
            id = link.trackId,
            source = link.source,
            sourceId = link.sourceId,
            url = link.canonicalUrl,
            title = meta?.title ?: link.fallbackTitle,
            author = meta?.author,
            thumbnailUrl = meta?.thumbnailUrl,
            durationMs = meta?.durationMs ?: 0L,
            loop = if (link.startMs > 0) {
                LoopSettings(startMs = link.startMs, loopStartMs = link.startMs)
            } else {
                LoopSettings.Default
            },
        )
        tracks.insertIgnore(track)
        return tracks.byId(track.id)
    }

    // ---------- Spotify ----------

    /** Link ze Spotify z udostepnionego tekstu; krotki spotify.link rozwija przez siec. */
    suspend fun resolveSpotify(text: String): SpotifyLink? = withContext(Dispatchers.IO) {
        runCatching { spotify.resolve(text) }.getOrNull()
    }

    /**
     * Pojedynczy utwor ze Spotify: szuka go na YouTube i zapisuje jak zwykly film.
     * Null, gdy na YouTube nie ma nic wystarczajaco podobnego.
     *
     * @throws com.loopa.app.resolve.ResolveException gdy Spotify nie pokaze utworu.
     */
    suspend fun addSpotifySong(link: SpotifyLink): SpotifyAdded? = withContext(Dispatchers.IO) {
        val song = spotify.song(link.id)
        val match = matcher.find(song) ?: return@withContext null
        SpotifyAdded(saveTracks(listOf(trackFor(song, match))).first(), match)
    }

    /** @throws com.loopa.app.resolve.ResolveException gdy Spotify nie pokaze playlisty. */
    suspend fun spotifyCollection(link: SpotifyLink): SpotifyCollection =
        withContext(Dispatchers.IO) { spotify.collection(link) }

    /**
     * Dopasowuje utwory do filmow na YouTube. Nic nie zapisuje - to robi dopiero
     * [saveTracks], gdy uzytkownik zatwierdzi. Null w wyniku = nie znaleziono.
     */
    suspend fun matchSongs(
        songs: List<SpotifySong>,
        onEach: (found: Boolean) -> Unit,
    ): List<Track?> = matcher.findAll(songs) { _, match -> onEach(match != null) }
        .mapIndexed { index, match -> match?.let { trackFor(songs[index], it) } }

    /**
     * Zapisuje filmy i oddaje ich wersje z bazy. Film, ktory juz byl w bibliotece,
     * zostaje nietkniety - razem ze swoja petla.
     */
    suspend fun saveTracks(list: List<Track>): List<Track> = db.withTransaction {
        list.map { track ->
            tracks.insertIgnore(track)
            tracks.byId(track.id) ?: track
        }
    }

    /** Dokłada wiele filmow naraz, w podanej kolejnosci, bez duplikatow. */
    suspend fun addAllToPlaylist(playlistId: Long, trackIds: List<String>) = db.withTransaction {
        trackIds.forEach { addToPlaylist(playlistId, it) }
    }

    /**
     * Gra film z YouTube'a, ale nazywa sie tak jak na Spotify - tytul i wykonawcy
     * stamtad sa czystsze niz tytuly filmow ("... (Official Lyric Video) [4K]").
     */
    private fun trackFor(song: SpotifySong, match: YouTubeMatch) = Track(
        id = "yt:${match.videoId}",
        source = Source.YOUTUBE,
        sourceId = match.videoId,
        url = LinkParser.youtubeUrl(match.videoId),
        title = song.title,
        author = song.artistLine.ifBlank { null } ?: match.channel,
        thumbnailUrl = match.thumbnailUrl,
        durationMs = match.durationMs,
    )

    suspend fun createPlaylist(name: String): Long =
        playlists.insert(Playlist(name = name.trim().ifEmpty { "Nowa playlista" }))

    suspend fun renamePlaylist(id: Long, name: String) = playlists.rename(id, name.trim())

    suspend fun deletePlaylist(id: Long) = playlists.delete(id)

    /** Dokłada film na koniec playlisty. Duplikaty sa dozwolone (celowo). */
    suspend fun addToPlaylist(playlistId: Long, trackId: String, allowDuplicate: Boolean = false): Long? {
        if (!allowDuplicate && items.countIn(playlistId, trackId) > 0) return null
        val pos = items.nextPosition(playlistId)
        return items.insert(PlaylistItem(playlistId = playlistId, trackId = trackId, position = pos))
    }

    suspend fun removeItem(itemId: Long) = items.delete(itemId)

    suspend fun deleteTrack(trackId: String) = tracks.delete(trackId)

    // ---------- petle ----------

    /** Domyslne ustawienia filmu - dzialaja wszedzie, gdzie wpis nie ma nadpisania. */
    suspend fun saveTrackLoop(trackId: String, loop: LoopSettings) {
        tracks.updateLoop(
            id = trackId,
            start = loop.startMs,
            loopStart = loop.loopStartMs,
            end = loop.endMs,
            mode = loop.repeatMode.name,
            count = loop.repeatCount,
        )
    }

    /** Ustawienia tylko dla tego wpisu w tej playliscie. */
    suspend fun saveItemLoop(itemId: Long, loop: LoopSettings) {
        val entry = items.entry(itemId) ?: return
        items.update(entry.item.copy(hasLoopOverride = true, loopOverride = loop))
    }

    /** Kasuje nadpisanie - wpis wraca do ustawien filmu. */
    suspend fun clearItemLoop(itemId: Long) {
        val entry = items.entry(itemId) ?: return
        items.update(entry.item.copy(hasLoopOverride = false, loopOverride = LoopSettings.Default))
    }

    suspend fun fillDuration(trackId: String, durationMs: Long) {
        if (durationMs > 0) tracks.fillDuration(trackId, durationMs)
    }

    // ---------- przewijanie playlisty ----------

    suspend fun savePlaylistSettings(playlist: Playlist) {
        playlists.updateSettings(
            id = playlist.id,
            autoAdvance = playlist.autoAdvance,
            advanceOnFinish = playlist.advanceOnFinish,
            useGlobalCount = playlist.useGlobalCount,
            globalCount = playlist.globalCount.coerceIn(1, 999),
            usePerItemCount = playlist.usePerItemCount,
        )
    }

    suspend fun setItemPlayCount(itemId: Long, count: Int) =
        items.setPlayCount(itemId, count.coerceIn(1, 999))

    suspend fun setPlayCountForAll(playlistId: Long, count: Int) =
        items.setPlayCountForAll(playlistId, count.coerceIn(1, 999))

    suspend fun refreshMetadata(trackId: String) {
        val track = tracks.byId(trackId) ?: return
        val link = LinkParser.parse(track.url) ?: return
        val meta = resolvers.metadata(link, force = true) ?: return
        tracks.updateMeta(trackId, meta.title, meta.author, meta.thumbnailUrl, meta.durationMs)
    }

    // ---------- kolejnosc ----------

    suspend fun move(playlistId: Long, fromIndex: Int, toIndex: Int) {
        val list = items.entries(playlistId).toMutableList()
        if (fromIndex !in list.indices || toIndex !in list.indices || fromIndex == toIndex) return
        val moved = list.removeAt(fromIndex)
        list.add(toIndex, moved)
        list.forEachIndexed { index, entry ->
            if (entry.item.position != index) items.setPosition(entry.item.id, index)
        }
    }
}
