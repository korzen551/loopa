package com.loopa.app.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

enum class Source { YOUTUBE, TIKTOK }

/** Co zrobic, gdy segment dojedzie do [LoopSettings.endMs]. */
enum class RepeatMode {
    /** Graj raz, potem nastepny film w kolejce. */
    OFF,

    /** Zapetl w nieskonczonosc od [LoopSettings.loopStartMs]. */
    LOOP,

    /** Powtorz [LoopSettings.repeatCount] razy, potem nastepny film. */
    COUNT,
}

/**
 * Trzy punkty na osi czasu:
 *  - [startMs]     - gdzie zaczyna sie PIERWSZE odtworzenie,
 *  - [loopStartMs] - gdzie zaczyna sie KAZDE kolejne (to sa te 9 sekund z zalozenia),
 *  - [endMs]       - gdzie segment sie konczy; 0 = do naturalnego konca filmu.
 */
data class LoopSettings(
    @ColumnInfo(name = "start_ms") val startMs: Long = 0L,
    @ColumnInfo(name = "loop_start_ms") val loopStartMs: Long = 0L,
    @ColumnInfo(name = "end_ms") val endMs: Long = 0L,
    @ColumnInfo(name = "repeat_mode") val repeatMode: RepeatMode = RepeatMode.OFF,
    @ColumnInfo(name = "repeat_count") val repeatCount: Int = 3,
) {
    /** Koniec segmentu w ms, z uwzglednieniem realnej dlugosci filmu. */
    fun effectiveEnd(durationMs: Long): Long =
        if (endMs > 0L && (durationMs <= 0L || endMs < durationMs)) endMs else durationMs

    val loops: Boolean get() = repeatMode != RepeatMode.OFF

    /** Czy w ogole odbiega od "graj od zera do konca". */
    val isCustom: Boolean
        get() = startMs > 0L || loopStartMs > 0L || endMs > 0L || repeatMode != RepeatMode.OFF

    companion object {
        val Default = LoopSettings()
    }
}

@Entity(tableName = "tracks")
data class Track(
    /** "yt:<videoId>" albo "tt:<videoId>" - stabilne miedzy playlistami. */
    @PrimaryKey val id: String,
    val source: Source,
    val sourceId: String,
    /** Kanoniczny link do strony z filmem (nie do streamu - ten wygasa). */
    val url: String,
    val title: String,
    val author: String?,
    val thumbnailUrl: String?,
    val durationMs: Long,
    val addedAt: Long = System.currentTimeMillis(),
    /** Domyslna petla filmu; wpis w playliscie moze ja nadpisac. */
    @Embedded val loop: LoopSettings = LoopSettings.Default,
)

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * Wylacznik glowny przewijania. Gdy false, playlista zachowuje sie jak TikTok:
     * biezacy film leci w kolko, a zmieniasz go wylacznie palcem. Pozostale
     * ustawienia ponizej maja sens dopiero, gdy to jest wlaczone.
     */
    val autoAdvance: Boolean = false,

    /** Po zakonczeniu filmu przejdz do nastepnego. */
    val advanceOnFinish: Boolean = false,

    /** Kazdy film w playliscie leci [globalCount] razy. */
    val useGlobalCount: Boolean = false,
    val globalCount: Int = 1,

    /** Kazdy film ma wlasna liczbe powtorzen ustawiona osobno. */
    val usePerItemCount: Boolean = false,
) {
    /** Czy podustawienia przewijania sa w ogole dostepne. */
    val subSettingsEnabled: Boolean get() = autoAdvance
}

@Entity(
    tableName = "playlist_items",
    foreignKeys = [
        ForeignKey(
            entity = Playlist::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Track::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId"), Index("trackId")],
)
data class PlaylistItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val playlistId: Long,
    val trackId: String,
    val position: Int,
    /**
     * Gdy true, ten wpis gra wg [loopOverride] zamiast ustawien filmu.
     * Dzieki temu ten sam klip moze leciec inaczej w dwoch playlistach.
     */
    val hasLoopOverride: Boolean = false,
    @Embedded(prefix = "ov_") val loopOverride: LoopSettings = LoopSettings.Default,

    /**
     * Ile razy ten konkretny film ma polecic, gdy playlista dziala w trybie
     * indywidualnym ([Playlist.usePerItemCount]).
     */
    val playCount: Int = 1,
)

/** Wpis playlisty + film + juz rozstrzygnieta petla. */
data class PlaylistEntry(
    @Embedded val item: PlaylistItem,
    @Relation(parentColumn = "trackId", entityColumn = "id") val track: Track,
) {
    val loop: LoopSettings get() = if (item.hasLoopOverride) item.loopOverride else track.loop
}

data class PlaylistWithCount(
    @Embedded val playlist: Playlist,
    val itemCount: Int,
    val coverUrl: String?,
)
