package com.loopa.app.resolve

import android.util.Log
import com.loopa.app.data.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Wybiera resolver po zrodle i trzyma krotkozyjacy cache adresow strumieni.
 *
 * Adresy NIGDY nie ida do bazy - wygasaja. W cache siedza tylko w pamieci, a gdy
 * przeterminuja sie w trakcie grania, ExoPlayer otwiera zrodlo jeszcze raz
 * i dostaje swiezy adres.
 *
 * Dwie rzeczy pilnuja, zeby czas ladowania nie zalezal od dlugosci playlisty:
 * blokada na film (dwa watki nigdy nie wyciagaja tego samego adresu rownolegle)
 * i podgrzewanie kolejnego klipu w tle, zanim do niego dojedziesz.
 */
class ResolverRegistry {

    private val youtube = YouTubeResolver()
    private val tiktok = TikTokResolver()

    private val streamCache = ConcurrentHashMap<String, ResolvedStream>()
    private val metadataCache = ConcurrentHashMap<String, TrackMetadata>()

    /** Jedna blokada na film - inaczej dwa watki robia te sama, kosztowna ekstrakcje. */
    private val locks = ConcurrentHashMap<String, Any>()

    /** Filmy, dla ktorych ekstrakcja padla - UI pokaze je w WebView (bez grania w tle). */
    private val embedOnly = ConcurrentHashMap.newKeySet<String>()

    /** Ostatni blad na film - zeby UI mialo co pokazac zamiast czarnego ekranu. */
    private val lastError = ConcurrentHashMap<String, String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefetchJobs = ConcurrentHashMap<String, Job>()

    private fun resolverFor(source: Source): StreamResolver = when (source) {
        Source.YOUTUBE -> youtube
        Source.TIKTOK -> tiktok
    }

    suspend fun metadata(link: ParsedLink, force: Boolean = false): TrackMetadata? =
        withContext(Dispatchers.IO) {
            if (!force) metadataCache[link.trackId]?.let { return@withContext it }
            runCatching { resolverFor(link.source).metadata(link) }
                .onSuccess { metadataCache[link.trackId] = it }
                .onFailure { Log.w(TAG, "Metadane nieudane dla ${link.trackId}", it) }
                .getOrNull()
        }

    /**
     * Blokujaca. Wolana z watku ladowania ExoPlayera - blokowanie jest tam w porzadku.
     *
     * Gdy inny watek wlasnie wyciaga ten sam film, czekamy na jego wynik zamiast
     * powtarzac prace: ekstrakcja z YouTube to kilka zapytan HTTP plus odszyfrowanie
     * podpisu, wiec robienie jej dwa razy naraz potrafi zauwazalnie spowolnic start.
     */
    @Throws(ResolveException::class)
    fun streamBlocking(link: ParsedLink): ResolvedStream {
        peek(link.trackId)?.let { return it }

        val lock = locks.getOrPut(link.trackId) { Any() }
        synchronized(lock) {
            // Ktos mogl rozwiazac ten film, gdy czekalismy pod blokada.
            peek(link.trackId)?.let { return it }

            return try {
                resolverFor(link.source).stream(link).also {
                    streamCache[link.trackId] = it
                    embedOnly.remove(link.trackId)
                    lastError.remove(link.trackId)
                }
            } catch (e: Exception) {
                embedOnly.add(link.trackId)
                streamCache.remove(link.trackId)
                lastError[link.trackId] = e.message ?: "Nieznany błąd"
                throw if (e is ResolveException) e else ResolveException(e.message ?: "Nieznany błąd", e)
            }
        }
    }

    suspend fun stream(link: ParsedLink): ResolvedStream? = withContext(Dispatchers.IO) {
        runCatching { streamBlocking(link) }.getOrNull()
    }

    fun peek(trackId: String): ResolvedStream? = streamCache[trackId]?.takeIf { it.isFresh }

    fun isEmbedOnly(trackId: String): Boolean = embedOnly.contains(trackId)

    fun errorFor(trackId: String): String? = lastError[trackId]

    fun invalidate(trackId: String) {
        streamCache.remove(trackId)
        embedOnly.remove(trackId)
        lastError.remove(trackId)
    }

    /**
     * Podgrzewa adres w tle, zeby przewiniecie na kolejny klip nie czekalo na
     * pelna ekstrakcje. Wywolania dla tego samego filmu skladaja sie w jedno.
     */
    fun prefetch(link: ParsedLink) {
        if (peek(link.trackId) != null) return
        if (prefetchJobs[link.trackId]?.isActive == true) return
        prefetchJobs[link.trackId] = scope.launch {
            runCatching { streamBlocking(link) }
                .onFailure { Log.d(TAG, "Podgrzewanie nieudane dla ${link.trackId}: ${it.message}") }
            prefetchJobs.remove(link.trackId)
        }
    }

    private companion object {
        const val TAG = "ResolverRegistry"
    }
}
