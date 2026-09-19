package com.loopa.app.resolve

/** Metadane pokazywane w bibliotece - pobierane raz, przy zapisie filmu. */
data class TrackMetadata(
    val title: String,
    val author: String?,
    val thumbnailUrl: String?,
    val durationMs: Long,
    /** Prawdziwy, dlugi adres - dla krotkich linkow TikToka. */
    val canonicalUrl: String? = null,
    /** Prawdziwe id - dla krotkich linkow TikToka. */
    val sourceId: String? = null,
)

/**
 * Adres strumienia gotowy do podania ExoPlayerowi. Wygasa (YouTube ~6h, TikTok
 * krocej), dlatego nigdy nie trafia do bazy - tylko do cache w pamieci.
 */
data class ResolvedStream(
    val url: String,
    /** Naglowki wymagane przy pobieraniu (TikTok bez Referera zwraca 403). */
    val headers: Map<String, String> = emptyMap(),
    val durationMs: Long = 0L,
    /** Kiedy uznajemy adres za nieswiezy. */
    val expiresAt: Long = System.currentTimeMillis() + DEFAULT_TTL_MS,
) {
    val isFresh: Boolean get() = System.currentTimeMillis() < expiresAt

    companion object {
        const val DEFAULT_TTL_MS = 30 * 60 * 1000L
    }
}

class ResolveException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface StreamResolver {
    /** Blokujaca - wolana z watku IO albo z watku ladowania ExoPlayera. */
    fun metadata(link: ParsedLink): TrackMetadata

    /** Blokujaca. Rzuca [ResolveException], gdy sie nie da (np. film usuniety). */
    fun stream(link: ParsedLink): ResolvedStream
}
