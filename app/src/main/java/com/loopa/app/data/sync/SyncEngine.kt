package com.loopa.app.data.sync

import android.content.Context
import android.util.Log
import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import com.loopa.app.data.AppDatabase
import com.loopa.app.data.Playlist
import com.loopa.app.data.PlaylistItem
import com.loopa.app.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.UUID

sealed interface SyncStatus {
    /** Nie polaczono z kontem - biblioteka zyje tylko na tym urzadzeniu. */
    data object Off : SyncStatus
    data object Working : SyncStatus
    data class Ok(val at: Long) : SyncStatus
    data class Failed(val message: String) : SyncStatus
}

/**
 * Wspolna biblioteka telefonu i komputera, jak konto w Spotify: zmiana na
 * jednym urzadzeniu pojawia sie na drugim.
 *
 * Kazde urzadzenie pamieta, jak biblioteka wygladala przy ostatniej udanej
 * synchronizacji. Porownanie tego z tym, co jest teraz tutaj, i z tym, co jest
 * w chmurze, mowi, co zmienilo sie po ktorej stronie - takze co zostalo
 * usuniete. Dzieki temu baza nie potrzebuje zadnych nowych kolumn.
 *
 * Synchronizacja rusza: przy starcie, kilka sekund po kazdej zmianie w bibliotece
 * i co minute (zeby zobaczyc zmiany z drugiego urzadzenia).
 */
class SyncEngine(context: Context, private val db: AppDatabase) {

    private val prefs = context.applicationContext.getSharedPreferences("loopa_sync", Context.MODE_PRIVATE)
    private val stateFile = File(context.applicationContext.filesDir, "sync-state.json")
    private val mutex = Mutex()
    private val nudges = Channel<Unit>(Channel.CONFLATED)

    private val _status = MutableStateFlow(if (token == null) SyncStatus.Off else SyncStatus.Working)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    val isConnected: Boolean get() = token != null

    private val token: String?
        get() = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun start(scope: CoroutineScope) {
        db.invalidationTracker.addObserver(object : InvalidationTracker.Observer(arrayOf("tracks", "playlists", "playlist_items")) {
            override fun onInvalidated(tables: Set<String>) {
                nudges.trySend(Unit)
            }
        })
        scope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { sync() }
                // Zmiana w bibliotece budzi wczesniej; inaczej zagladamy co minute.
                withTimeoutOrNull(PERIOD_MS) {
                    nudges.receive()
                    delay(DEBOUNCE_MS)
                    while (nudges.tryReceive().isSuccess) Unit
                }
            }
        }
    }

    /** Laczy z kontem: zapisuje token, znajduje (albo zaklada) plik biblioteki. */
    suspend fun connect(newToken: String): Result<Unit> = withContext(Dispatchers.IO) {
        val clean = newToken.trim()
        runCatching {
            val gistId = GistStore(clean).findOrCreate()
            prefs.edit().putString(KEY_TOKEN, clean).putString(KEY_GIST, gistId).apply()
            sync()
        }.onFailure { _status.value = SyncStatus.Failed(it.message ?: "Nie udało się połączyć.") }
    }

    /** Biblioteka na tym urzadzeniu zostaje - przestaje sie tylko synchronizowac. */
    fun disconnect() {
        prefs.edit().clear().apply()
        stateFile.delete()
        _status.value = SyncStatus.Off
    }

    fun syncSoon() {
        nudges.trySend(Unit)
    }

    suspend fun sync() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val token = token ?: run {
                _status.value = SyncStatus.Off
                return@withLock
            }
            _status.value = SyncStatus.Working
            try {
                runOnce(GistStore(token))
                _status.value = SyncStatus.Ok(System.currentTimeMillis())
            } catch (e: Exception) {
                Log.w(TAG, "Synchronizacja nieudana", e)
                _status.value = SyncStatus.Failed(e.message ?: "Synchronizacja się nie udała.")
            }
        }
    }

    private suspend fun runOnce(store: GistStore) {
        var gistId = prefs.getString(KEY_GIST, null) ?: store.findOrCreate().also { saveGist(it) }
        val state = loadState()
        val local = exportLocal(state.uids)
        val remote = try {
            store.read(gistId)
        } catch (e: GistStore.GistGoneException) {
            gistId = store.findOrCreate().also { saveGist(it) }
            store.read(gistId)
        }

        // Pusty plik w chmurze przy niepustej historii znaczy, ze ktos go skasowal
        // albo zalozyl od nowa - nie wolno wtedy uznac, ze wszystko usunieto.
        val base = if (remote.tracks.isEmpty() && remote.playlists.isEmpty() && state.base.tracks.isNotEmpty()) {
            SyncDoc()
        } else {
            state.base
        }

        val merged = mergeDocs(base, local, remote)
        if (merged != local) importDoc(merged, local, state.uids)
        if (merged != remote) store.write(gistId, merged)
        saveState(SyncState(merged, state.uids.filterKeys { it in merged.playlists }.toMutableMap()))
    }

    // ---------- baza <-> wspolny format ----------

    private suspend fun exportLocal(uids: MutableMap<String, Long>): SyncDoc {
        val tracks = db.trackDao().all()
        val playlists = db.playlistDao().allOnce()
        val items = db.playlistItemDao().all()

        val uidById = uids.entries.associate { (uid, id) -> id to uid }.toMutableMap()
        for (p in playlists) {
            if (p.id !in uidById) {
                val uid = UUID.randomUUID().toString()
                uidById[p.id] = uid
                uids[uid] = p.id
            }
        }

        return SyncDoc(
            tracks = tracks.associate { t ->
                t.id to SyncTrack(t.source, t.sourceId, t.url, t.title, t.author, t.thumbnailUrl, t.durationMs, t.addedAt, t.loop)
            },
            playlists = playlists.associate { p ->
                uidById.getValue(p.id) to SyncPlaylist(
                    p.name, p.createdAt, p.autoAdvance, p.advanceOnFinish, p.useGlobalCount, p.globalCount, p.usePerItemCount,
                )
            },
            items = items.groupBy { it.playlistId }.flatMap { (playlistId, list) ->
                val uid = uidById[playlistId] ?: return@flatMap emptyList()
                list.sortedBy { it.position }.mapIndexed { index, item ->
                    SyncDoc.itemKey(uid, item.trackId) to SyncItem(
                        uid, item.trackId, index, item.hasLoopOverride, item.loopOverride, item.playCount,
                    )
                }
            }.toMap(),
        )
    }

    /**
     * Usuwa wylacznie to, co bylo w [exported] - czyli znane w chwili eksportu.
     * Film czy playlista dodane w trakcie synchronizacji (np. import ze Spotify)
     * nie moga zniknac tylko dlatego, ze jeszcze ich nie bylo w porownaniu.
     */
    private suspend fun importDoc(doc: SyncDoc, exported: SyncDoc, uids: MutableMap<String, Long>) = db.withTransaction {
        val trackDao = db.trackDao()
        val playlistDao = db.playlistDao()
        val itemDao = db.playlistItemDao()

        // Filmy - pobrane kopie (localUri) sa tylko tego telefonu i zostaja.
        val existingTracks = trackDao.all().associateBy { it.id }
        for ((id, t) in doc.tracks) {
            val current = existingTracks[id]
            if (current == null) {
                trackDao.insertIgnore(
                    Track(id, t.source, t.sourceId, t.url, t.title, t.author, t.thumbnailUrl, t.durationMs, t.addedAt, loop = t.loop)
                )
            } else {
                val updated = current.copy(
                    url = t.url, title = t.title, author = t.author, thumbnailUrl = t.thumbnailUrl,
                    durationMs = t.durationMs, addedAt = t.addedAt, loop = t.loop,
                )
                if (updated != current) trackDao.update(updated)
            }
        }
        for (id in exported.tracks.keys - doc.tracks.keys) trackDao.delete(id)

        // Playlisty
        val existingPlaylists = playlistDao.allOnce().associateBy { it.id }
        for ((uid, p) in doc.playlists) {
            val current = uids[uid]?.let { existingPlaylists[it] }
            if (current == null) {
                uids[uid] = playlistDao.insert(
                    Playlist(
                        name = p.name, createdAt = p.createdAt, autoAdvance = p.autoAdvance,
                        advanceOnFinish = p.advanceOnFinish, useGlobalCount = p.useGlobalCount,
                        globalCount = p.globalCount, usePerItemCount = p.usePerItemCount,
                    )
                )
            } else {
                val updated = current.copy(
                    name = p.name, autoAdvance = p.autoAdvance, advanceOnFinish = p.advanceOnFinish,
                    useGlobalCount = p.useGlobalCount, globalCount = p.globalCount, usePerItemCount = p.usePerItemCount,
                )
                if (updated != current) playlistDao.update(updated)
            }
        }
        for (uid in exported.playlists.keys - doc.playlists.keys) uids[uid]?.let { playlistDao.delete(it) }

        // Wpisy: dokladnie te i w tej kolejnosci, co we wspolnej bibliotece.
        val currentItems = itemDao.all().groupBy { it.playlistId }
        val wanted = doc.items.values.groupBy { it.playlist }
        for (uid in doc.playlists.keys) {
            val playlistId = uids[uid] ?: continue
            val desired = wanted[uid].orEmpty().sortedWith(compareBy({ it.position }, { it.track }))
            val current = currentItems[playlistId].orEmpty().associateBy { it.trackId }
            val desiredTracks = desired.map { it.track }.toSet()
            current.values
                .filter { it.trackId !in desiredTracks && SyncDoc.itemKey(uid, it.trackId) in exported.items }
                .forEach { itemDao.delete(it.id) }
            desired.forEachIndexed { index, d ->
                val c = current[d.track]
                if (c == null) {
                    itemDao.insert(
                        PlaylistItem(
                            playlistId = playlistId, trackId = d.track, position = index,
                            hasLoopOverride = d.hasLoopOverride, loopOverride = d.loopOverride, playCount = d.playCount,
                        )
                    )
                } else {
                    val updated = c.copy(
                        position = index, hasLoopOverride = d.hasLoopOverride,
                        loopOverride = d.loopOverride, playCount = d.playCount,
                    )
                    if (updated != c) itemDao.update(updated)
                }
            }
        }
    }

    // ---------- stan ----------

    private class SyncState(val base: SyncDoc, val uids: MutableMap<String, Long>)

    private fun loadState(): SyncState = runCatching {
        val root = JSONObject(stateFile.readText())
        val uidsJson = root.optJSONObject("uids")
        val uids = uidsJson?.keys()?.asSequence()?.associateWith { uidsJson.getLong(it) }.orEmpty()
        SyncState(SyncDoc.fromJson(root.optJSONObject("base")?.toString()), uids.toMutableMap())
    }.getOrElse { SyncState(SyncDoc(), mutableMapOf()) }

    private fun saveState(state: SyncState) {
        val root = JSONObject()
            .put("base", JSONObject(state.base.toJson()))
            .put("uids", JSONObject(state.uids as Map<*, *>))
        val tmp = File(stateFile.path + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(stateFile)
    }

    private fun saveGist(id: String) = prefs.edit().putString(KEY_GIST, id).apply()

    private companion object {
        const val TAG = "SyncEngine"
        const val KEY_TOKEN = "token"
        const val KEY_GIST = "gist_id"
        const val PERIOD_MS = 60_000L
        const val DEBOUNCE_MS = 2_500L
    }
}
