package com.loopa.app.media

import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.loopa.app.data.LoopSettings
import com.loopa.app.data.PlaylistEntry
import com.loopa.app.data.RepeatMode
import com.loopa.app.data.Source
import com.loopa.app.data.Track
import com.loopa.app.resolve.ParsedLink

/** Film + petla, ktora ma go dotyczyc w tym konkretnym kontekscie odtwarzania. */
data class PlayableItem(
    val track: Track,
    val loop: LoopSettings,
    /** Id wpisu playlisty, gdy gramy z playlisty; null przy pojedynczym filmie. */
    val playlistItemId: Long? = null,
) {
    companion object {
        fun of(entry: PlaylistEntry) = PlayableItem(entry.track, entry.loop, entry.item.id)
        fun of(track: Track) = PlayableItem(track, track.loop, null)
    }
}

/**
 * Adres-zastepnik. Prawdziwy link do strumienia powstaje dopiero przy otwarciu
 * zrodla przez ExoPlayera, w [LoopaMediaSourceFactory] - dzieki temu nic, co
 * wygasa, nie musi byc znane z gory ani zapisywane.
 */
object PlayUri {
    private const val SCHEME = "loopa"

    fun forTrack(track: Track): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority("play")
        .appendQueryParameter("id", track.id)
        .appendQueryParameter("src", track.source.name)
        .appendQueryParameter("sid", track.sourceId)
        .appendQueryParameter("u", track.url)
        .build()

    fun isPlayUri(uri: Uri): Boolean = uri.scheme == SCHEME

    /** Odtwarza [ParsedLink] z adresu-zastepnika, bez dotykania bazy. */
    fun toLink(uri: Uri): ParsedLink? {
        if (!isPlayUri(uri)) return null
        val source = runCatching { Source.valueOf(uri.getQueryParameter("src").orEmpty()) }.getOrNull() ?: return null
        val sourceId = uri.getQueryParameter("sid") ?: return null
        val url = uri.getQueryParameter("u") ?: return null
        return ParsedLink(source = source, sourceId = sourceId, canonicalUrl = url)
    }

    fun trackId(uri: Uri): String? = uri.getQueryParameter("id")
}

object LoopKeys {
    const val START = "loopa.start"
    const val LOOP_START = "loopa.loopStart"
    const val END = "loopa.end"
    const val MODE = "loopa.mode"
    const val COUNT = "loopa.count"
    const val ITEM_ID = "loopa.itemId"
    const val SOURCE = "loopa.source"
}

fun LoopSettings.toBundle(playlistItemId: Long?, source: Source): Bundle = Bundle().apply {
    putLong(LoopKeys.START, startMs)
    putLong(LoopKeys.LOOP_START, loopStartMs)
    putLong(LoopKeys.END, endMs)
    putString(LoopKeys.MODE, repeatMode.name)
    putInt(LoopKeys.COUNT, repeatCount)
    putLong(LoopKeys.ITEM_ID, playlistItemId ?: -1L)
    putString(LoopKeys.SOURCE, source.name)
}

fun MediaItem?.loopSettings(): LoopSettings {
    val extras = this?.mediaMetadata?.extras ?: return LoopSettings.Default
    if (!extras.containsKey(LoopKeys.START)) return LoopSettings.Default
    return LoopSettings(
        startMs = extras.getLong(LoopKeys.START),
        loopStartMs = extras.getLong(LoopKeys.LOOP_START),
        endMs = extras.getLong(LoopKeys.END),
        repeatMode = runCatching { RepeatMode.valueOf(extras.getString(LoopKeys.MODE).orEmpty()) }
            .getOrDefault(RepeatMode.OFF),
        repeatCount = extras.getInt(LoopKeys.COUNT, 3),
    )
}

fun MediaItem?.playlistItemId(): Long? =
    this?.mediaMetadata?.extras?.getLong(LoopKeys.ITEM_ID, -1L)?.takeIf { it >= 0L }

fun PlayableItem.toMediaItem(): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(track.title)
        .setArtist(track.author ?: track.source.label)
        .setAlbumTitle(track.source.label)
        .setArtworkUri(track.thumbnailUrl?.toUri())
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .setExtras(loop.toBundle(playlistItemId, track.source))
        .build()

    return MediaItem.Builder()
        .setMediaId(track.id)
        .setMediaMetadata(metadata)
        // localConfiguration nie przezywa drogi kontroler -> sesja, wiec adres
        // jedzie w requestMetadata i jest odtwarzany po stronie serwisu.
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder()
                .setMediaUri(PlayUri.forTrack(track))
                .build()
        )
        .build()
}

val Source.label: String
    get() = when (this) {
        Source.YOUTUBE -> "YouTube"
        Source.TIKTOK -> "TikTok"
    }
