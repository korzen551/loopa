package com.loopa.app.resolve

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

    override fun metadata(link: ParsedLink): TrackMetadata {
        val pageUrl = canonical(link)
        val realId = idInUrl.find(pageUrl)?.groupValues?.get(1) ?: link.sourceId

        oEmbed(pageUrl)?.let { json ->
            return TrackMetadata(
                title = json.optString("title").ifBlank { "TikTok $realId" },
                author = json.optString("author_name").ifBlank { null },
                thumbnailUrl = json.optString("thumbnail_url").ifBlank { null },
                durationMs = 0L, // oEmbed nie podaje dlugosci - uzupelni ja odtwarzacz.
                canonicalUrl = pageUrl,
                sourceId = realId,
            )
        }

        // oEmbed niedostepny - sprobuj wyciagnac z samej strony.
        val item = runCatching { itemStruct(pageUrl) }.getOrNull()
        return TrackMetadata(
            title = item?.optString("desc")?.ifBlank { null } ?: "TikTok $realId",
            author = item?.optJSONObject("author")?.optString("uniqueId"),
            thumbnailUrl = item?.optJSONObject("video")?.optString("cover")?.ifBlank { null },
            durationMs = (item?.optJSONObject("video")?.optLong("duration") ?: 0L) * 1000L,
            canonicalUrl = pageUrl,
            sourceId = realId,
        )
    }

    override fun stream(link: ParsedLink): ResolvedStream {
        val pageUrl = canonical(link)
        val (html, response) = Net.getString(pageUrl, desktopHeaders())
        val cookies = response.headers("set-cookie")
            .mapNotNull { it.substringBefore(';').takeIf { c -> c.contains('=') } }
            .joinToString("; ")

        val item = itemStruct(pageUrl, html)
            ?: throw ResolveException("Nie znalazlem danych filmu na stronie TikToka")
        val video = item.optJSONObject("video")
            ?: throw ResolveException("Wpis TikToka nie zawiera wideo (zdjecie?)")

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

        val headers = buildMap {
            put("User-Agent", Net.DESKTOP_UA)
            put("Referer", "https://www.tiktok.com/")
            put("Accept", "*/*")
            put("Accept-Encoding", "identity;q=1, *;q=0")
            if (cookies.isNotEmpty()) put("Cookie", cookies)
        }

        return ResolvedStream(
            url = playUrl,
            headers = headers,
            durationMs = video.optLong("duration") * 1000L,
            // Podpisane adresy TikToka zyja krotko.
            expiresAt = System.currentTimeMillis() + 20 * 60 * 1000L,
        )
    }

    /** Krotkie linki (vm./vt./t/) trzeba najpierw rozwinac. */
    private fun canonical(link: ParsedLink): String =
        if (link.needsRedirectResolve || !link.canonicalUrl.contains("/video/")) {
            Net.finalUrl(link.canonicalUrl).substringBefore('?')
        } else {
            link.canonicalUrl
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
