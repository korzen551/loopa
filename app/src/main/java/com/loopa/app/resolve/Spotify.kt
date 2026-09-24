package com.loopa.app.resolve

import org.json.JSONArray
import org.json.JSONObject

enum class SpotifyKind(val path: String) {
    TRACK("track"),
    PLAYLIST("playlist"),
    ALBUM("album"),
}

data class SpotifyLink(val kind: SpotifyKind, val id: String)

/** Utwor tak, jak opisuje go Spotify - tego szukamy potem na YouTube. */
data class SpotifySong(
    val title: String,
    val artists: List<String>,
    val durationMs: Long,
) {
    val artistLine: String get() = artists.joinToString(", ")
}

data class SpotifyCollection(
    val link: SpotifyLink,
    val name: String,
    /** Autor playlisty albo wykonawca albumu. */
    val owner: String?,
    val coverUrl: String?,
    val songs: List<SpotifySong>,
) {
    /**
     * Publiczny podglad Spotify pokazuje najwyzej [SpotifyClient.PREVIEW_LIMIT]
     * pozycji, wiec pelna setka znaczy, ze playlista moze byc dluzsza.
     */
    val mayBeTruncated: Boolean
        get() = link.kind == SpotifyKind.PLAYLIST && songs.size >= SpotifyClient.PREVIEW_LIMIT
}

object SpotifyLinks {
    /** Dowolne segmenty przed rodzajem: /intl-pl/, /embed/, stare /user/<nazwa>/. */
    private val WEB = Regex(
        """open\.spotify\.com/(?:[^/?#\s]+/)*?(track|playlist|album)/([A-Za-z0-9]{22})""",
        RegexOption.IGNORE_CASE,
    )
    private val URI = Regex("""spotify:(track|playlist|album):([A-Za-z0-9]{22})""", RegexOption.IGNORE_CASE)

    /** Nowe linki z "Udostepnij" w apce Spotify - trzeba je najpierw rozwinac. */
    private val SHORT = Regex("""https?://(?:spotify\.link|spoti\.fi)/[A-Za-z0-9_-]+""", RegexOption.IGNORE_CASE)

    fun parse(text: String?): SpotifyLink? {
        if (text.isNullOrBlank()) return null
        val match = WEB.find(text) ?: URI.find(text) ?: return null
        val kind = SpotifyKind.entries.first { it.path.equals(match.groupValues[1], ignoreCase = true) }
        return SpotifyLink(kind, match.groupValues[2])
    }

    fun shortLink(text: String?): String? = text?.let { SHORT.find(it)?.value }

    fun mentions(text: String?): Boolean = parse(text) != null || shortLink(text) != null
}

/**
 * Czyta utwory, playlisty i albumy ze Spotify bez konta i bez klucza API - z tej
 * samej publicznej strony podgladu, ktora Spotify daje do osadzania na stronach
 * (open.spotify.com/embed/...). Siedzi w niej JSON z tytulami, wykonawcami
 * i dlugosciami.
 *
 * Ograniczenie tej drogi: podglad playlisty pokazuje najwyzej 100 utworow.
 */
class SpotifyClient {

    private val nextData = Regex(
        """<script[^>]+id="__NEXT_DATA__"[^>]*>(.*?)</script>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** Link z udostepnionego tekstu; krotki link spotify.link rozwija przez siec. */
    fun resolve(text: String): SpotifyLink? {
        SpotifyLinks.parse(text)?.let { return it }
        val short = SpotifyLinks.shortLink(text) ?: return null
        val (body, response) = Net.getString(short, pageHeaders())
        // Przekierowanie zwykle konczy sie wprost na open.spotify.com, ale bywa
        // tez strona posrednia z docelowym linkiem w tresci.
        return SpotifyLinks.parse(response.request.url.toString()) ?: SpotifyLinks.parse(body)
    }

    fun song(id: String): SpotifySong {
        val entity = entity(SpotifyLink(SpotifyKind.TRACK, id))
        val artists = entity.optJSONArray("artists").names()
        return SpotifySong(
            title = entity.optString("name").ifBlank { entity.optString("title") }
                .ifBlank { throw ResolveException("Spotify nie podał tytułu tego utworu.") },
            artists = artists,
            durationMs = entity.optLong("duration").coerceAtLeast(0L),
        )
    }

    fun collection(link: SpotifyLink): SpotifyCollection {
        val entity = entity(link)
        val list = entity.optJSONArray("trackList") ?: JSONArray()
        val songs = (0 until list.length()).mapNotNull { i ->
            val item = list.optJSONObject(i) ?: return@mapNotNull null
            // Playlisty moga zawierac odcinki podcastow - tych nie ma czego szukac.
            val uri = item.optString("uri")
            if (uri.isNotEmpty() && !uri.startsWith("spotify:track:") && !uri.startsWith("spotify:local:")) {
                return@mapNotNull null
            }
            val title = item.optString("title").ifBlank { return@mapNotNull null }
            SpotifySong(
                title = title,
                artists = splitArtists(item.optString("subtitle")),
                durationMs = item.optLong("duration").coerceAtLeast(0L),
            )
        }
        return SpotifyCollection(
            link = link,
            name = entity.optString("name").ifBlank { entity.optString("title") }.ifBlank { "Spotify" },
            owner = entity.optString("subtitle").ifBlank { null },
            coverUrl = coverOf(entity),
            songs = songs,
        )
    }

    private fun entity(link: SpotifyLink): JSONObject {
        val (html, response) = Net.getString("https://open.spotify.com/embed/${link.kind.path}/${link.id}", pageHeaders())
        // Komunikaty stad trafiaja wprost do arkusza "Udostepnij".
        if (!response.isSuccessful) {
            throw ResolveException("Spotify odrzucił zapytanie (kod ${response.code}). Spróbuj za chwilę.")
        }
        val raw = nextData.find(html)?.groupValues?.get(1)
            ?: throw ResolveException("Spotify zmienił stronę podglądu i nie umiem jej odczytać.")
        return runCatching {
            JSONObject(raw).getJSONObject("props").getJSONObject("pageProps")
                .getJSONObject("state").getJSONObject("data").getJSONObject("entity")
        }.getOrNull()
            ?: throw ResolveException(
                when (link.kind) {
                    SpotifyKind.PLAYLIST -> "Spotify nie pokazuje tej playlisty — może jest prywatna albo usunięta."
                    SpotifyKind.ALBUM -> "Spotify nie pokazuje tego albumu."
                    SpotifyKind.TRACK -> "Spotify nie pokazuje tego utworu."
                }
            )
    }

    /**
     * Wykonawcy w podgladzie playlisty przychodza jednym napisem, sklejeni
     * przecinkiem i TWARDA spacja. Dzielimy tylko po takim sklejeniu - zwykly
     * przecinek bywa czescia nazwy ("Tyler, The Creator").
     */
    private fun splitArtists(subtitle: String): List<String> =
        subtitle.split(", ").map { it.trim() }.filter { it.isNotEmpty() }

    private fun JSONArray?.names(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it)?.optString("name")?.trim()?.ifBlank { null } }
    }

    private fun coverOf(entity: JSONObject): String? {
        val sources = entity.optJSONObject("coverArt")?.optJSONArray("sources")
            ?: entity.optJSONObject("visualIdentity")?.optJSONArray("image")
            ?: return null
        return (0 until sources.length())
            .mapNotNull { sources.optJSONObject(it) }
            .maxByOrNull { it.optInt("maxWidth", it.optInt("width", 0)) }
            ?.optString("url")
            ?.ifBlank { null }
    }

    private fun pageHeaders() = mapOf(
        "User-Agent" to Net.DESKTOP_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "pl-PL,pl;q=0.9,en;q=0.8",
    )

    companion object {
        const val PREVIEW_LIMIT = 100
    }
}
