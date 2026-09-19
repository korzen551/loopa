package com.loopa.app.resolve

import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Wyciaga adres strumienia przez NewPipeExtractor.
 *
 * Swiadomie bierzemy strumien *muxed* (obraz + dzwiek w jednym pliku, zwykle 360p).
 * Wyzsze jakosci YouTube serwuje jako osobne sciezki wideo i audio, co wymagaloby
 * sklejania dwoch zrodel i rozwiazywania adresu z gory dla calej playlisty -
 * a tak adres domyka sie leniwie, przy otwarciu strumienia, i sam sie odswieza
 * gdy wygasnie. Przy klipach na telefonie to dobry kompromis.
 */
class YouTubeResolver : StreamResolver {

    override fun metadata(link: ParsedLink): TrackMetadata {
        val extractor = fetch(link)
        return TrackMetadata(
            title = extractor.name.orEmpty().ifBlank { link.fallbackTitle },
            author = extractor.uploaderName,
            thumbnailUrl = bestThumbnail(extractor),
            durationMs = extractor.length.coerceAtLeast(0) * 1000L,
        )
    }

    override fun stream(link: ParsedLink): ResolvedStream {
        val extractor = fetch(link)
        val muxed = extractor.videoStreams
            .filterNotNull()
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !it.content.isNullOrBlank() }
            .maxByOrNull { heightOf(it) }

        val url = muxed?.content
            ?: extractor.audioStreams
                .filterNotNull()
                .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !it.content.isNullOrBlank() }
                .maxByOrNull { it.averageBitrate }
                ?.content
            ?: throw ResolveException("YouTube nie zwrocil zadnego strumienia dla ${link.sourceId}")

        return ResolvedStream(
            url = url,
            headers = mapOf("User-Agent" to Net.DESKTOP_UA),
            durationMs = extractor.length.coerceAtLeast(0) * 1000L,
            // Adresy googlevideo maja zwykle ~6h zycia; tniemy z zapasem.
            expiresAt = System.currentTimeMillis() + 3 * 60 * 60 * 1000L,
        )
    }

    private fun fetch(link: ParsedLink): StreamExtractor = try {
        ServiceList.YouTube.getStreamExtractor(LinkParser.youtubeUrl(link.sourceId)).apply { fetchPage() }
    } catch (e: Exception) {
        throw ResolveException("Nie udalo sie odczytac filmu z YouTube (${e.message})", e)
    }

    private fun heightOf(stream: VideoStream): Int =
        stream.resolution?.substringBefore('p')?.filter { it.isDigit() }?.toIntOrNull() ?: 0

    private fun bestThumbnail(extractor: StreamExtractor): String? = runCatching {
        extractor.thumbnails.maxByOrNull { it.height }?.url
    }.getOrNull() ?: "https://i.ytimg.com/vi/${extractor.id}/hqdefault.jpg"
}
