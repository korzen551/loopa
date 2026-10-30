package com.loopa.app.data.sync

import com.loopa.app.data.LoopSettings
import com.loopa.app.data.RepeatMode
import com.loopa.app.data.Source
import org.json.JSONObject

/**
 * Wspolna biblioteka - ten sam format czyta i zapisuje Loopa na komputerze
 * (desktop/lib/sync.mjs). Tylko to, co ma byc takie samo na kazdym urzadzeniu:
 * bez sciezek do pobranych plikow, ktore istnieja tylko na tym telefonie.
 *
 * Playlisty maja wlasne, wspolne identyfikatory (uid) - lokalne numery z bazy
 * sa na kazdym urzadzeniu inne. Wpis playlisty to para playlista + film.
 */
data class SyncDoc(
    val tracks: Map<String, SyncTrack> = emptyMap(),
    val playlists: Map<String, SyncPlaylist> = emptyMap(),
    val items: Map<String, SyncItem> = emptyMap(),
) {
    fun toJson(): String = JSONObject().apply {
        put("version", 1)
        put("tracks", JSONObject().apply { tracks.toSortedMap().forEach { (k, v) -> put(k, v.toJson()) } })
        put("playlists", JSONObject().apply { playlists.toSortedMap().forEach { (k, v) -> put(k, v.toJson()) } })
        put("items", JSONObject().apply { items.toSortedMap().forEach { (k, v) -> put(k, v.toJson()) } })
    }.toString()

    companion object {
        fun fromJson(text: String?): SyncDoc {
            if (text.isNullOrBlank()) return SyncDoc()
            val root = JSONObject(text)
            return SyncDoc(
                tracks = root.optJSONObject("tracks").entries { SyncTrack.fromJson(it) },
                playlists = root.optJSONObject("playlists").entries { SyncPlaylist.fromJson(it) },
                items = root.optJSONObject("items").entries { SyncItem.fromJson(it) },
            )
        }

        private fun <T> JSONObject?.entries(parse: (JSONObject) -> T?): Map<String, T> {
            if (this == null) return emptyMap()
            return keys().asSequence().mapNotNull { key -> optJSONObject(key)?.let(parse)?.let { key to it } }.toMap()
        }

        fun itemKey(playlistUid: String, trackId: String) = "$playlistUid/$trackId"
    }
}

data class SyncTrack(
    val source: Source,
    val sourceId: String,
    val url: String,
    val title: String,
    val author: String?,
    val thumbnailUrl: String?,
    val durationMs: Long,
    val addedAt: Long,
    val loop: LoopSettings,
) {
    fun toJson() = JSONObject().apply {
        put("source", source.name.lowercase())
        put("sourceId", sourceId)
        put("url", url)
        put("title", title)
        put("author", author ?: JSONObject.NULL)
        put("thumbnailUrl", thumbnailUrl ?: JSONObject.NULL)
        put("durationMs", durationMs)
        put("addedAt", addedAt)
        put("loop", loop.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject): SyncTrack? {
            val source = runCatching { Source.valueOf(o.getString("source").uppercase()) }.getOrNull() ?: return null
            return SyncTrack(
                source = source,
                sourceId = o.optString("sourceId"),
                url = o.optString("url"),
                title = o.optString("title"),
                author = o.optStringOrNull("author"),
                thumbnailUrl = o.optStringOrNull("thumbnailUrl"),
                durationMs = o.optLong("durationMs"),
                addedAt = o.optLong("addedAt"),
                loop = loopFromJson(o.optJSONObject("loop")),
            )
        }
    }
}

data class SyncPlaylist(
    val name: String,
    val createdAt: Long,
    val autoAdvance: Boolean,
    val advanceOnFinish: Boolean,
    val useGlobalCount: Boolean,
    val globalCount: Int,
    val usePerItemCount: Boolean,
) {
    fun toJson() = JSONObject().apply {
        put("name", name)
        put("createdAt", createdAt)
        put("autoAdvance", autoAdvance)
        put("advanceOnFinish", advanceOnFinish)
        put("useGlobalCount", useGlobalCount)
        put("globalCount", globalCount)
        put("usePerItemCount", usePerItemCount)
    }

    companion object {
        fun fromJson(o: JSONObject) = SyncPlaylist(
            name = o.optString("name").ifBlank { "Playlista" },
            createdAt = o.optLong("createdAt"),
            autoAdvance = o.optBoolean("autoAdvance"),
            advanceOnFinish = o.optBoolean("advanceOnFinish"),
            useGlobalCount = o.optBoolean("useGlobalCount"),
            globalCount = o.optInt("globalCount", 1),
            usePerItemCount = o.optBoolean("usePerItemCount"),
        )
    }
}

data class SyncItem(
    val playlist: String,
    val track: String,
    val position: Int,
    val hasLoopOverride: Boolean,
    val loopOverride: LoopSettings,
    val playCount: Int,
) {
    fun toJson() = JSONObject().apply {
        put("playlist", playlist)
        put("track", track)
        put("position", position)
        put("hasLoopOverride", hasLoopOverride)
        put("loopOverride", loopOverride.toJson())
        put("playCount", playCount)
    }

    companion object {
        fun fromJson(o: JSONObject): SyncItem? {
            val playlist = o.optString("playlist").ifBlank { return null }
            val track = o.optString("track").ifBlank { return null }
            return SyncItem(
                playlist = playlist,
                track = track,
                position = o.optInt("position"),
                hasLoopOverride = o.optBoolean("hasLoopOverride"),
                loopOverride = loopFromJson(o.optJSONObject("loopOverride")),
                playCount = o.optInt("playCount", 1),
            )
        }
    }
}

private fun LoopSettings.toJson() = JSONObject().apply {
    put("startMs", startMs)
    put("loopStartMs", loopStartMs)
    put("endMs", endMs)
    put("repeatMode", repeatMode.name)
    put("repeatCount", repeatCount)
}

private fun loopFromJson(o: JSONObject?): LoopSettings {
    if (o == null) return LoopSettings.Default
    return LoopSettings(
        startMs = o.optLong("startMs"),
        loopStartMs = o.optLong("loopStartMs"),
        endMs = o.optLong("endMs"),
        repeatMode = runCatching { RepeatMode.valueOf(o.optString("repeatMode")) }.getOrDefault(RepeatMode.OFF),
        repeatCount = o.optInt("repeatCount", 3),
    )
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).ifBlank { null }

/**
 * Laczy trzy wersje: z ostatniej synchronizacji ([base]), ta z tego urzadzenia
 * i ta z chmury. Rekord zmieniony tylko po jednej stronie bierzemy stamtad -
 * takze usuniecie. Zmieniony po obu stronach: wygrywa to urzadzenie, chyba ze
 * tu go usunieto, a tam edytowano - wtedy zostaje (lepiej nie zgubic zmiany).
 */
fun mergeDocs(base: SyncDoc, local: SyncDoc, remote: SyncDoc): SyncDoc = SyncDoc(
    tracks = merge3(base.tracks, local.tracks, remote.tracks),
    playlists = merge3(base.playlists, local.playlists, remote.playlists),
    items = merge3(base.items, local.items, remote.items),
).withoutOrphans()

private fun <T> merge3(base: Map<String, T>, local: Map<String, T>, remote: Map<String, T>): Map<String, T> {
    val out = HashMap<String, T>()
    for (key in base.keys + local.keys + remote.keys) {
        val b = base[key]
        val l = local[key]
        val r = remote[key]
        val value = when {
            l == b -> r
            r == b -> l
            l == null -> r
            else -> l
        }
        if (value != null) out[key] = value
    }
    return out
}

/** Wpisy wskazujace na usuniety film albo playliste znikaja razem z nimi. */
private fun SyncDoc.withoutOrphans() = copy(
    items = items.filterValues { it.playlist in playlists && it.track in tracks },
)
