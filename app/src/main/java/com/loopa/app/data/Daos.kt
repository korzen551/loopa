package com.loopa.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(track: Track): Long

    @Update
    suspend fun update(track: Track)

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun byId(id: String): Track?

    @Query("SELECT * FROM tracks WHERE id = :id")
    fun observe(id: String): Flow<Track?>

    @Query("SELECT * FROM tracks ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<Track>>

    @Query("SELECT * FROM tracks ORDER BY addedAt DESC")
    suspend fun all(): List<Track>

    @Query(
        "UPDATE tracks SET start_ms = :start, loop_start_ms = :loopStart, end_ms = :end, " +
            "repeat_mode = :mode, repeat_count = :count WHERE id = :id"
    )
    suspend fun updateLoop(id: String, start: Long, loopStart: Long, end: Long, mode: String, count: Int)

    @Query("UPDATE tracks SET durationMs = :durationMs WHERE id = :id AND durationMs <= 0")
    suspend fun fillDuration(id: String, durationMs: Long)

    @Query("UPDATE tracks SET title = :title, author = :author, thumbnailUrl = :thumb, durationMs = :durationMs WHERE id = :id")
    suspend fun updateMeta(id: String, title: String, author: String?, thumb: String?, durationMs: Long)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE tracks SET localUri = :uri, localDurationMs = :durationMs WHERE id = :id")
    suspend fun setLocalCopy(id: String, uri: String?, durationMs: Long)

    @Query("SELECT * FROM tracks WHERE localUri IS NOT NULL")
    suspend fun downloaded(): List<Track>

    /** Filmy, ktore nie naleza do zadnej playlisty. */
    @Query("SELECT * FROM tracks WHERE id NOT IN (SELECT trackId FROM playlist_items) ORDER BY addedAt DESC")
    fun observeUnfiled(): Flow<List<Track>>
}

@Dao
interface PlaylistDao {
    @Insert
    suspend fun insert(playlist: Playlist): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observe(id: Long): Flow<Playlist?>

    @Query(
        "SELECT p.*, " +
            "(SELECT COUNT(*) FROM playlist_items i WHERE i.playlistId = p.id) AS itemCount, " +
            "(SELECT t.thumbnailUrl FROM playlist_items i JOIN tracks t ON t.id = i.trackId " +
            " WHERE i.playlistId = p.id ORDER BY i.position LIMIT 1) AS coverUrl " +
            "FROM playlists p ORDER BY p.createdAt DESC"
    )
    fun observeAllWithCounts(): Flow<List<PlaylistWithCount>>

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Playlist>>

    @Query(
        "UPDATE playlists SET autoAdvance = :autoAdvance, advanceOnFinish = :advanceOnFinish, " +
            "useGlobalCount = :useGlobalCount, globalCount = :globalCount, " +
            "usePerItemCount = :usePerItemCount WHERE id = :id"
    )
    suspend fun updateSettings(
        id: Long,
        autoAdvance: Boolean,
        advanceOnFinish: Boolean,
        useGlobalCount: Boolean,
        globalCount: Int,
        usePerItemCount: Boolean,
    )
}

@Dao
interface PlaylistItemDao {
    @Transaction
    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position ASC")
    fun observeEntries(playlistId: Long): Flow<List<PlaylistEntry>>

    @Transaction
    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun entries(playlistId: Long): List<PlaylistEntry>

    @Transaction
    @Query("SELECT * FROM playlist_items WHERE id = :itemId")
    suspend fun entry(itemId: Long): PlaylistEntry?

    @Insert
    suspend fun insert(item: PlaylistItem): Long

    @Update
    suspend fun update(item: PlaylistItem)

    @Query("DELETE FROM playlist_items WHERE id = :itemId")
    suspend fun delete(itemId: Long)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun nextPosition(playlistId: Long): Int

    @Query("UPDATE playlist_items SET position = :position WHERE id = :itemId")
    suspend fun setPosition(itemId: Long, position: Int)

    @Query("SELECT playlistId FROM playlist_items WHERE trackId = :trackId")
    suspend fun playlistIdsFor(trackId: String): List<Long>

    @Query("SELECT COUNT(*) FROM playlist_items WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun countIn(playlistId: Long, trackId: String): Int

    @Query("UPDATE playlist_items SET playCount = :count WHERE id = :itemId")
    suspend fun setPlayCount(itemId: Long, count: Int)

    @Query("UPDATE playlist_items SET playCount = :count WHERE playlistId = :playlistId")
    suspend fun setPlayCountForAll(playlistId: Long, count: Int)
}
