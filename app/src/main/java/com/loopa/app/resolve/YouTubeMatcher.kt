package com.loopa.app.resolve

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.text.Normalizer
import kotlin.math.abs

/** Film z YouTube'a, ktory gra dany utwor ze Spotify. */
data class YouTubeMatch(
    val videoId: String,
    val videoTitle: String,
    val channel: String?,
    val thumbnailUrl: String?,
    val durationMs: Long,
)

/**
 * Szuka na YouTube nagrania, ktore odpowiada utworowi ze Spotify.
 *
 * Spotify nie wypuszcza dzwieku poza swoja apke, wiec udostepniony utwor
 * zamieniamy na film z YouTube'a - i to on potem gra w Loopa.
 *
 * Najmocniejszy sygnal to dlugosc: to samo nagranie trwa na obu serwisach tyle
 * samo co do sekundy, a koncertowka, cover czy teledysk z dlugim wstepem juz nie.
 * Do tego tytul, wykonawca i kara za wersje, ktorych nikt nie udostepnial
 * ("sped up", karaoke, na zywo...).
 *
 * Najpierw zwykle wyszukiwanie filmow - trafia w teledysk albo oficjalne audio,
 * gdy maja ten sam dzwiek co album. Gdy najlepsze trafienie nie pochodzi od
 * samego wykonawcy czy wytworni, dopytujemy YouTube Music: zna oficjalne
 * nagrania wprost z wytworni, ktorych nikt nie zdejmie za prawa autorskie.
 */
class YouTubeMatcher {

    /** Blokujaca - jedno albo dwa zapytania HTTP. Null, gdy nic nie pasuje wystarczajaco. */
    fun find(song: SpotifySong): YouTubeMatch? {
        val query = queryFor(song)
        val videos = best(song, search(query, YoutubeSearchQueryHandlerFactory.VIDEOS))
        if (videos != null && videos.rating.official && videos.rating.total >= CONFIDENT) return videos.match

        val music = runCatching { best(song, search(query, YoutubeSearchQueryHandlerFactory.MUSIC_SONGS)) }
            .getOrNull()
        return listOfNotNull(videos, music).maxByOrNull { it.rating.total }?.match
    }

    /**
     * Dopasowuje cala liste, kilka utworow naraz - przy playliscie na 100 pozycji
     * robi to roznice miedzy kilkunastoma sekundami a paroma minutami. Wiecej
     * rownoleglych zapytan to juz proszenie sie o blokade od YouTube'a.
     */
    suspend fun findAll(
        songs: List<SpotifySong>,
        onEach: (index: Int, match: YouTubeMatch?) -> Unit,
    ): List<YouTubeMatch?> = coroutineScope {
        val gate = Semaphore(PARALLEL_SEARCHES)
        songs.mapIndexed { index, song ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val match = runCatching { find(song) }
                        .recoverCatching { find(song) } // jedna ponowna proba na chwilowy blad sieci
                        .getOrNull()
                    onEach(index, match)
                    match
                }
            }
        }.awaitAll()
    }

    private fun search(query: String, filter: String): List<Candidate> {
        val extractor = ServiceList.YouTube.getSearchExtractor(query, listOf(filter), "")
        extractor.fetchPage()
        return extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .filter { it.streamType != StreamType.LIVE_STREAM && it.duration > 0 }
            .take(MAX_CANDIDATES)
            .mapIndexedNotNull { rank, item ->
                val id = LinkParser.parse(item.url)?.sourceId ?: return@mapIndexedNotNull null
                Candidate(
                    videoId = id,
                    title = item.name.orEmpty(),
                    channel = item.uploaderName,
                    durationSec = item.duration,
                    thumbnailUrl = runCatching { item.thumbnails.maxByOrNull { it.height }?.url }.getOrNull()
                        ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                    verified = runCatching { item.isUploaderVerified }.getOrDefault(false),
                    rank = rank,
                )
            }
    }

    private fun best(song: SpotifySong, candidates: List<Candidate>): Scored? =
        candidates.mapNotNull { candidate -> rate(song, candidate)?.let { Scored(candidate.toMatch(), it) } }
            .maxByOrNull { it.rating.total }

    internal data class Candidate(
        val videoId: String,
        val title: String,
        val channel: String?,
        val durationSec: Long,
        val thumbnailUrl: String?,
        val verified: Boolean,
        val rank: Int,
    ) {
        fun toMatch() = YouTubeMatch(videoId, title, channel, thumbnailUrl, durationSec * 1000L)
    }

    /** [official] = kanal wykonawcy, wytworni (VEVO) albo oficjalne nagranie z YouTube Music. */
    internal data class Rating(val total: Double, val official: Boolean)

    private data class Scored(val match: YouTubeMatch, val rating: Rating)

    internal companion object {
        const val PARALLEL_SEARCHES = 4
        const val MAX_CANDIDATES = 12

        /** Oficjalne trafienie z taka ocena konczy szukanie. */
        const val CONFIDENT = 0.9

        /** Ponizej tego lepiej przyznac, ze nie znalezlismy, niz wcisnac zly utwor. */
        const val ACCEPTABLE = 0.62

        /**
         * Slowa oznaczajace inna wersje nagrania. Dzialaja w obie strony: jesli
         * Spotify ma remiks, szukamy remiksu, a jesli nie ma - remiks odpada.
         */
        private val VARIANT_WORDS = listOf(
            "live", "cover", "karaoke", "instrumental", "remix", "sped up", "speed up", "slowed",
            "reverb", "nightcore", "8d", "acoustic", "piano", "reaction", "tutorial", "lesson",
            "mashup", "parody", "bass boosted", "hour", "hours", "loop", "extended", "concert",
            "tribute", "fan made", "fanmade", "chipmunk", "reversed", "lyrics translation",
            "clean", "censored", "cenzura", "na zywo", "koncert",
        ).map { " ${normalize(it)} " }

        /** Dopisek Spotify po myslniku: wydanie ("Remastered 2011") albo wersja ("Live"). */
        private val SUFFIX = Regex("""\s+-\s+(.*)$""")
        private val FEATURING = Regex("""[(\[]\s*(?:feat\.?|ft\.?|featuring|with)\s[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)

        fun queryFor(song: SpotifySong): String {
            val artists = song.artists.take(2).joinToString(" ")
            // Dopisek wydania ("Remastered 2011", "From ...") tylko zaweza wyniki,
            // ale dopisek wersji ("Polish Remix", "Live") to juz inne nagranie.
            val suffix = SUFFIX.find(FEATURING.replace(song.title, " "))?.groupValues?.get(1).orEmpty()
            val version = suffix.takeIf { variantsIn(it).isNotEmpty() }.orEmpty()
            return "$artists ${coreTitle(song.title)} $version".replace(Regex("""\s+"""), " ").trim()
        }

        /** "Sunflower - Spider-Man: Into the Spider-Verse" -> "Sunflower". */
        fun coreTitle(title: String): String {
            val withoutFeat = FEATURING.replace(title, " ")
            val core = SUFFIX.replace(withoutFeat, "").trim()
            return core.ifBlank { withoutFeat.trim() }
        }

        fun normalize(text: String): String =
            Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("""\p{M}+"""), "")
                .replace('ł', 'l')
                .replace("&", " and ")
                .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
                .trim()

        private fun tokens(text: String): Set<String> =
            normalize(text).split(' ').filter { it.isNotEmpty() }.toSet()

        private fun variantsIn(text: String): Set<String> {
            val padded = " ${normalize(text)} "
            return VARIANT_WORDS.filter { it in padded }.toSet()
        }

        /** Ocena 0..~1.2; null, gdy kandydat na pewno nie jest tym utworem. */
        fun rate(song: SpotifySong, candidate: Candidate): Rating? {
            val candidateTokens = tokens(candidate.title)
            val channelTokens = tokens(candidate.channel.orEmpty())
            val everywhere = candidateTokens + channelTokens

            val coreTokens = tokens(coreTitle(song.title)).ifEmpty { tokens(song.title) }
            if (coreTokens.isEmpty()) return null
            val titleScore = coreTokens.count { it in candidateTokens }.toDouble() / coreTokens.size

            val artistHits = song.artists.map { artist ->
                val parts = tokens(artist)
                parts.isNotEmpty() && parts.all { it in everywhere }
            }
            val artistScore = when {
                artistHits.isEmpty() -> 0.5
                artistHits.size == 1 -> if (artistHits[0]) 1.0 else 0.0
                else -> (if (artistHits[0]) 0.7 else 0.0) +
                    0.3 * artistHits.drop(1).count { it } / (artistHits.size - 1)
            }

            val durationScore = if (song.durationMs <= 0L) {
                0.5
            } else {
                val diffSec = abs(candidate.durationSec - song.durationMs / 1000.0)
                when {
                    diffSec <= 2.5 -> 1.0
                    diffSec <= 4.5 -> 0.9
                    diffSec <= 8.0 -> 0.7
                    diffSec <= 15.0 -> 0.45
                    diffSec <= 30.0 -> 0.2
                    diffSec <= 60.0 -> 0.05
                    else -> 0.0
                }
            }

            // Tytul musi sie zgadzac przynajmniej w polowie, a wykonawca musi sie
            // gdzies pojawic - chyba ze tytul i dlugosc zgadzaja sie idealnie.
            if (titleScore < 0.5) return null
            if (artistScore == 0.0 && !(titleScore == 1.0 && durationScore >= 0.9)) return null

            val songVariants = variantsIn(song.title)
            val candidateVariants = variantsIn(candidate.title)
            val mismatched = (candidateVariants - songVariants).size + (songVariants - candidateVariants).size
            val penalty = (mismatched * 0.3).coerceAtMost(0.6)

            // Przy rownie dobrej dlugosci wolimy kanal samego wykonawcy albo wytworni
            // niz kanal z tekstami piosenek - te bywaja zdejmowane za prawa autorskie.
            val channel = normalize(candidate.channel.orEmpty())
            val primaryArtist = song.artists.firstOrNull()?.let { normalize(it).replace(" ", "") }.orEmpty()
            val artistChannel = primaryArtist.length >= 3 && primaryArtist in channel.replace(" ", "")
            val topic = channel.endsWith(" topic")
            val vevo = "vevo" in channel
            val official = artistChannel || topic || vevo

            var bonus = 0.0
            if (artistChannel) bonus += 0.07
            if (topic) bonus += 0.05
            if (vevo) bonus += 0.04
            // "Official" w tytule dopisuje sobie kazdy, kto wrzuca cudzy teledysk.
            if ((official || candidate.verified) && " official " in " ${normalize(candidate.title)} ") bonus += 0.03
            if (candidate.verified) bonus += 0.01
            bonus += when (candidate.rank) {
                0 -> 0.03
                1 -> 0.02
                2 -> 0.01
                else -> 0.0
            }

            val total = 0.40 * titleScore + 0.25 * artistScore + 0.35 * durationScore + bonus - penalty
            return Rating(total, official).takeIf { total >= ACCEPTABLE }
        }
    }
}
