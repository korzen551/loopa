package com.loopa.app.resolve

import okhttp3.Response
import org.json.JSONObject
import java.net.URLEncoder

/**
 * TikTok nie ma publicznego API do odtwarzania, wiec:
 *  - metadane bierzemy z oficjalnego oEmbed (stabilne, bez klucza),
 *  - adres streamu wyskrobujemy z JSON-a osadzonego w stronie filmu.
 *
 * Ten drugi krok jest z natury kruchy - TikTok zmienia strukture strony co jakis
 * czas. Gdy padnie, [ResolverRegistry] przelacza klip na oficjalny embed w WebView
 * (wtedy bez grania w tle) zamiast pokazywac blad.
 */
class TikTokResolver : StreamResolver {

    private val embeddedJson = Regex(
        """<script[^>]+id="__UNIVERSAL_DATA_FOR_REHYDRATION__"[^>]*>(.*?)</script>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val legacyJson = Regex(
        """<script[^>]+id="SIGI_STATE"[^>]*>(.*?)</script>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val idInUrl = Regex("""/(?:video|photo)/(\d{6,})""")
    private val frontityJson = Regex(
        """<script[^>]+id="__FRONTITY_CONNECT_STATE__"[^>]*>(.*?)</script>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    override fun metadata(link: ParsedLink): TrackMetadata {
        val pageUrl = canonical(link)
        LinkParser.tiktokMusicId(pageUrl)?.let { return musicMetadata(pageUrl, it) }
        val realId = idInUrl.find(pageUrl)?.groupValues?.get(1) ?: link.sourceId

        // Dla wpisow ze zdjeciami oEmbed odpowiada samym szkieletem - bez tytulu,
        // autora i miniatury. Wtedy tez trzeba siegnac do strony wpisu.
        oEmbed(pageUrl)?.takeIf { it.optString("thumbnail_url").isNotBlank() }?.let { json ->
            return TrackMetadata(
                title = json.optString("title").ifBlank { "TikTok $realId" },
                author = json.optString("author_name").ifBlank { null },
                thumbnailUrl = json.optString("thumbnail_url").ifBlank { null },
                durationMs = 0L, // oEmbed nie podaje dlugosci - uzupelni ja odtwarzacz.
                canonicalUrl = pageUrl,
                sourceId = realId,
            )
        }

        val item = runCatching { itemStruct(pageUrl) }.getOrNull()
        val video = item?.optJSONObject("video")
        val music = item?.optJSONObject("music")
        val isPhotoPost = item?.optJSONObject("imagePost") != null
        return TrackMetadata(
            title = item?.optString("desc")?.ifBlank { null } ?: "TikTok $realId",
            author = item?.optJSONObject("author")?.let { author ->
                author.optString("nickname").ifBlank { author.optString("uniqueId") }.ifBlank { null }
            },
            thumbnailUrl = video?.optString("cover")?.ifBlank { null }
                ?: item?.optJSONObject("imagePost")?.optJSONObject("cover")?.optJSONObject("imageURL")
                    ?.optJSONArray("urlList")?.optString(0)?.ifBlank { null },
            // Wpis ze zdjeciami trwa tyle, co jego muzyka.
            durationMs = (if (isPhotoPost) music?.optLong("duration") else video?.optLong("duration"))
                ?.times(1000L) ?: 0L,
            canonicalUrl = pageUrl,
            sourceId = realId,
        )
    }

    override fun stream(link: ParsedLink): ResolvedStream {
        val pageUrl = canonical(link)
        LinkParser.tiktokMusicId(pageUrl)?.let { return musicStream(it) }

        val (html, response) = Net.getString(pageUrl, desktopHeaders())
        val item = itemStruct(pageUrl, html)
            ?: throw ResolveException("Nie znalazlem danych filmu na stronie TikToka")
        val headers = mediaHeaders(cookiesOf(response))

        photoPost(item, headers)?.let { return it }

        val video = item.optJSONObject("video")
            ?: throw ResolveException("Wpis TikToka nie zawiera wideo.")

        val playUrl = listOf("playAddr", "downloadAddr")
            .firstNotNullOfOrNull { video.optString(it).takeIf { url -> url.startsWith("http") } }
            ?: video.optJSONArray("bitrateInfo")
                ?.let { arr ->
                    (0 until arr.length()).firstNotNullOfOrNull { i ->
                        arr.optJSONObject(i)?.optJSONObject("PlayAddr")
                            ?.optJSONArray("UrlList")?.optString(0)?.takeIf { it.startsWith("http") }
                    }
                }
            ?: throw ResolveException("Brak adresu strumienia w danych TikToka")

        return ResolvedStream(
            url = playUrl,
            headers = headers,
            durationMs = video.optLong("duration") * 1000L,
            // Podpisane adresy TikToka zyja krotko.
            expiresAt = System.currentTimeMillis() + 20 * 60 * 1000L,
        )
    }

    /**
     * Krotkie linki (vm./vt./t/) trzeba najpierw rozwinac. Adres /photo/ zawsze
     * zamieniamy na /video/ - pod /photo/ TikTok nie oddaje danych wpisu (takie
     * adresy siedza tez w bazie z wczesniejszych wersji).
     */
    private fun canonical(link: ParsedLink): String {
        val url = link.canonicalUrl.replace("/photo/", "/video/")
        if (!link.needsRedirectResolve && LinkParser.tiktokMusicId(url) != null) return url
        val expanded = if (link.needsRedirectResolve || !url.contains("/video/")) {
            Net.finalUrl(url).substringBefore('?')
        } else {
            url
        }
        return expanded.replace("/photo/", "/video/")
    }

    private fun cookiesOf(response: Response) = response.headers("set-cookie")
        .mapNotNull { it.substringBefore(';').takeIf { c -> c.contains('=') } }
        .joinToString("; ")

    private fun mediaHeaders(cookies: String) = buildMap {
        put("User-Agent", Net.DESKTOP_UA)
        put("Referer", "https://www.tiktok.com/")
        put("Accept", "*/*")
        put("Accept-Encoding", "identity;q=1, *;q=0")
        if (cookies.isNotEmpty()) put("Cookie", cookies)
    }

    // ---------- dzwieki (tiktok.com/music/...) ----------

    private class Sound(val music: JSONObject, val cover: String?, val artist: String?, val cookies: String)

    /**
     * Strona dzwieku nie zawiera pliku audio, ale jej wersja do osadzania podaje
     * filmy, ktore go uzywaja. Kazdy z nich niesie w swoich danych ten sam dzwiek
     * (music.playUrl) - bierzemy go z pierwszego, ktory sie otworzy.
     */
    private fun sound(musicId: String): Sound {
        var state: JSONObject? = null
        for (attempt in 0 until 3) {
            val html = runCatching {
                Net.getString("https://www.tiktok.com/embed/music/$musicId", desktopHeaders()).first
            }.getOrNull()
            state = html?.let { frontityJson.find(it)?.groupValues?.get(1) }
                ?.let { raw -> runCatching { JSONObject(raw).getJSONObject("source").getJSONObject("data") }.getOrNull() }
                ?.let { data ->
                    data.keys().asSequence().firstNotNullOfOrNull { key ->
                        data.optJSONObject(key)?.takeIf { it.has("videoList") }
                    }
                }
            if (state != null) break
        }
        if (state == null) throw ResolveException("Nie znalazłem tego dźwięku na TikToku.")

        val info = state.optJSONObject("embedInfo")
        val videos = state.optJSONArray("videoList")
        for (i in 0 until minOf(videos?.length() ?: 0, 5)) {
            val v = videos?.optJSONObject(i) ?: continue
            val url = "https://www.tiktok.com/@${v.optString("authorUniqueId")}/video/${v.optString("id")}"
            val (html, response) = runCatching { Net.getString(url, desktopHeaders()) }.getOrNull() ?: continue
            val music = itemStruct(url, html)?.optJSONObject("music") ?: continue
            if (music.optString("id") == musicId && music.optString("playUrl").startsWith("http")) {
                return Sound(
                    music = music,
                    cover = info?.optString("coverUrl")?.ifBlank { null },
                    artist = info?.optString("artist")?.ifBlank { null },
                    cookies = cookiesOf(response),
                )
            }
        }
        throw ResolveException("Nie udało się pobrać tego dźwięku z TikToka.")
    }

    private fun musicMetadata(pageUrl: String, musicId: String): TrackMetadata {
        val s = sound(musicId)
        val title = s.music.optString("title").ifBlank { "Dźwięk z TikToka" }
        val author = s.music.optString("authorName").ifBlank { null } ?: s.artist
        return TrackMetadata(
            title = "♬ $title" + (author?.let { " – $it" } ?: ""),
            author = author,
            thumbnailUrl = s.music.optString("coverLarge").ifBlank { null } ?: s.cover,
            durationMs = s.music.optLong("duration") * 1000L,
            canonicalUrl = pageUrl,
            sourceId = "music_$musicId",
        )
    }

    /** Sam dzwiek, a na ekranie jego okladka - tak jak przy wpisie ze zdjeciami. */
    private fun musicStream(musicId: String): ResolvedStream {
        val s = sound(musicId)
        val cover = s.music.optString("coverLarge").ifBlank { null } ?: s.cover
        return ResolvedStream(
            url = s.music.optString("playUrl"),
            headers = mediaHeaders(s.cookies),
            durationMs = (s.music.optLong("duration") * 1000L).coerceAtLeast(0L),
            expiresAt = System.currentTimeMillis() + 20 * 60 * 1000L,
            images = listOfNotNull(cover),
        )
    }

    /**
     * Wpis ze zdjeciami nie ma pliku wideo - jest lista zdjec i osobna muzyka.
     * Odtwarzacz gra wtedy sama muzyke, a zdjecia pokazuje ekran (karuzela).
     */
    private fun photoPost(item: JSONObject, headers: Map<String, String>): ResolvedStream? {
        val list = item.optJSONObject("imagePost")?.optJSONArray("images") ?: return null
        val images = (0 until list.length()).mapNotNull { i ->
            list.optJSONObject(i)?.optJSONObject("imageURL")?.optJSONArray("urlList")
                ?.optString(0)?.takeIf { it.startsWith("http") }
        }
        if (images.isEmpty()) return null

        val music = item.optJSONObject("music")
        val audioUrl = music?.optString("playUrl")?.takeIf { it.startsWith("http") }
            ?: throw ResolveException("Ten wpis ze zdjęciami nie ma dźwięku, a Loopa potrzebuje go do odtwarzania.")

        return ResolvedStream(
            url = audioUrl,
            headers = headers,
            durationMs = (music.optLong("duration") * 1000L).coerceAtLeast(0L),
            // Podpisane adresy zdjec TikToka zyja kilka dni, ale muzyka i ciasteczka krocej.
            expiresAt = System.currentTimeMillis() + 20 * 60 * 1000L,
            images = images,
        )
    }

    private fun itemStruct(pageUrl: String, preloadedHtml: String? = null): JSONObject? {
        val html = preloadedHtml ?: Net.getString(pageUrl, desktopHeaders()).first
        val realId = idInUrl.find(pageUrl)?.groupValues?.get(1)

        embeddedJson.find(html)?.groupValues?.get(1)?.let { raw ->
            runCatching {
                JSONObject(raw)
                    .getJSONObject("__DEFAULT_SCOPE__")
                    .getJSONObject("webapp.video-detail")
                    .getJSONObject("itemInfo")
                    .getJSONObject("itemStruct")
            }.getOrNull()?.let { return it }
        }

        legacyJson.find(html)?.groupValues?.get(1)?.let { raw ->
            runCatching {
                val modules = JSONObject(raw).getJSONObject("ItemModule")
                val key = realId?.takeIf { modules.has(it) } ?: modules.keys().asSequence().firstOrNull()
                key?.let { modules.getJSONObject(it) }
            }.getOrNull()?.let { return it }
        }

        return null
    }

    private fun oEmbed(pageUrl: String): JSONObject? = runCatching {
        val encoded = URLEncoder.encode(pageUrl, "UTF-8")
        val (body, response) = Net.getString("https://www.tiktok.com/oembed?url=$encoded")
        if (!response.isSuccessful) null else JSONObject(body)
    }.getOrNull()

    private fun desktopHeaders() = mapOf(
        "User-Agent" to Net.DESKTOP_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "pl-PL,pl;q=0.9,en;q=0.8",
    )
}
