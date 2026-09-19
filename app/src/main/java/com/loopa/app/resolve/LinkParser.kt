package com.loopa.app.resolve

import com.loopa.app.data.Source

/**
 * Wynik rozpoznania linku. [sourceId] dla krotkich linkow TikToka jest jeszcze
 * kodem przekierowania - prawdziwe id dociaga [TikTokResolver] przy pierwszym uzyciu.
 */
data class ParsedLink(
    val source: Source,
    val sourceId: String,
    val canonicalUrl: String,
    /** ?t=9 / ?start=9 z linku - od razu ustawiamy jako punkt startu. */
    val startMs: Long = 0L,
    /** True, gdy [sourceId] to jeszcze kod krotkiego linku (vm.tiktok.com/...). */
    val needsRedirectResolve: Boolean = false,
) {
    val trackId: String get() = when (source) {
        Source.YOUTUBE -> "yt:$sourceId"
        Source.TIKTOK -> "tt:$sourceId"
    }

    val fallbackTitle: String get() = when (source) {
        Source.YOUTUBE -> "YouTube $sourceId"
        Source.TIKTOK -> "TikTok $sourceId"
    }
}

object LinkParser {

    private val URL_IN_TEXT = Regex("""https?://\S+""")

    private val YT_WATCH = Regex("""[?&]v=([A-Za-z0-9_-]{6,})""")
    private val YT_SHORT = Regex("""youtu\.be/([A-Za-z0-9_-]{6,})""")
    private val YT_PATH = Regex("""youtube\.com/(?:shorts|embed|live|v)/([A-Za-z0-9_-]{6,})""")

    private val TT_FULL = Regex("""tiktok\.com/@[^/]+/(?:video|photo)/(\d{6,})""")
    private val TT_BARE_ID = Regex("""tiktok\.com/v/(\d{6,})""")
    private val TT_SHORT = Regex("""(?:vm|vt)\.tiktok\.com/([A-Za-z0-9]+)""")
    private val TT_T_PATH = Regex("""tiktok\.com/t/([A-Za-z0-9]+)""")

    /** Sekundy z ?t=90 / ?t=1m30s / ?start=90. */
    private val T_PARAM = Regex("""[?&](?:t|start|time_continue)=([0-9hms]+)""", RegexOption.IGNORE_CASE)
    private val HMS = Regex("""(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s?)?""", RegexOption.IGNORE_CASE)

    /**
     * Wyciaga pierwszy obslugiwany link z dowolnego tekstu (share z TikToka wysyla
     * caly opis razem z linkiem).
     */
    fun parse(text: String?): ParsedLink? {
        if (text.isNullOrBlank()) return null
        val candidates = URL_IN_TEXT.findAll(text).map { it.value.trimEnd('.', ',', ')', ']', '"') }.toList()
        val urls = if (candidates.isEmpty()) listOf(text.trim()) else candidates
        return urls.firstNotNullOfOrNull { parseSingle(it) }
    }

    private fun parseSingle(url: String): ParsedLink? {
        val startMs = parseStartMs(url)

        YT_SHORT.find(url)?.let {
            return ParsedLink(Source.YOUTUBE, it.groupValues[1], youtubeUrl(it.groupValues[1]), startMs)
        }
        YT_PATH.find(url)?.let {
            return ParsedLink(Source.YOUTUBE, it.groupValues[1], youtubeUrl(it.groupValues[1]), startMs)
        }
        if (url.contains("youtube.com", ignoreCase = true)) {
            YT_WATCH.find(url)?.let {
                return ParsedLink(Source.YOUTUBE, it.groupValues[1], youtubeUrl(it.groupValues[1]), startMs)
            }
        }

        TT_FULL.find(url)?.let {
            return ParsedLink(Source.TIKTOK, it.groupValues[1], normalizeTikTok(url), startMs)
        }
        TT_BARE_ID.find(url)?.let {
            return ParsedLink(Source.TIKTOK, it.groupValues[1], normalizeTikTok(url), startMs)
        }
        TT_SHORT.find(url)?.let {
            return ParsedLink(Source.TIKTOK, "short_" + it.groupValues[1], stripQuery(url), startMs, needsRedirectResolve = true)
        }
        TT_T_PATH.find(url)?.let {
            return ParsedLink(Source.TIKTOK, "short_" + it.groupValues[1], stripQuery(url), startMs, needsRedirectResolve = true)
        }
        return null
    }

    fun youtubeUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    fun isSupported(text: String?): Boolean = parse(text) != null

    private fun normalizeTikTok(url: String) = stripQuery(url).replace("://m.tiktok.com", "://www.tiktok.com")

    private fun stripQuery(url: String) = url.substringBefore('?').substringBefore('#').trimEnd('/')

    private fun parseStartMs(url: String): Long {
        val raw = T_PARAM.find(url)?.groupValues?.get(1) ?: return 0L
        raw.toLongOrNull()?.let { return it * 1000L }
        val m = HMS.matchEntire(raw) ?: return 0L
        val h = m.groupValues[1].toLongOrNull() ?: 0L
        val min = m.groupValues[2].toLongOrNull() ?: 0L
        val s = m.groupValues[3].toLongOrNull() ?: 0L
        return (h * 3600 + min * 60 + s) * 1000L
    }
}
