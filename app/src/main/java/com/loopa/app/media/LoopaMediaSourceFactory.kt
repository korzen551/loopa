package com.loopa.app.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.loopa.app.resolve.Net
import com.loopa.app.resolve.ResolveException
import com.loopa.app.resolve.ResolverRegistry
import java.io.IOException

/**
 * Zamienia adresy `loopa://play?...` na prawdziwe adresy strumieni i owija kazde
 * zrodlo w [LoopSegmentMediaSource], ktory robi z punktow petli czesc osi czasu.
 *
 * Rozwiazywanie dzieje sie leniwie, przy KAZDYM otwarciu zrodla (takze po
 * przewinieciu i po wznowieniu), wiec gdy podpisany adres wygasnie w trakcie
 * grania, ExoPlayer po prostu dostaje swiezy przy ponownym otwarciu.
 *
 * Pod spodem siedzi cache na dysku: kazde powtorzenie filmu to nowe otwarcie
 * zrodla, a bez cache oznaczaloby to sciaganie tego samego klipu z serwerow
 * TikToka czy YouTube'a przy kazdym obrocie petli.
 */
@UnstableApi
class LoopaMediaSourceFactory(
    context: Context,
    resolvers: ResolverRegistry,
    cache: Cache,
    private val onFullDuration: (trackId: String, durationMs: Long) -> Unit,
) : MediaSource.Factory {

    private val httpFactory = OkHttpDataSource.Factory(Net.client)
        .setUserAgent(Net.DESKTOP_UA)

    private val cachedHttpFactory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(httpFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private val resolvingFactory = ResolvingDataSource.Factory(cachedHttpFactory, LoopaResolver(resolvers))

    /**
     * Owiniecie w DefaultDataSource jest tu konieczne, odkad filmy moga byc
     * pobrane do galerii: sam OkHttp nie otworzy adresu `content://`. Schematy
     * lokalne obsluguje DefaultDataSource (bez cache - plik juz jest na dysku),
     * a wszystko sieciowe (w tym nasze `loopa://`) leci przez warstwe
     * rozwiazujaca adresy i cache.
     */
    private val dataSourceFactory = DefaultDataSource.Factory(context, resolvingFactory)

    private val delegate = DefaultMediaSourceFactory(dataSourceFactory)

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory =
        apply { delegate.setDrmSessionManagerProvider(provider) }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory =
        apply { delegate.setLoadErrorHandlingPolicy(policy) }

    override fun getSupportedTypes(): IntArray = delegate.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource =
        LoopSegmentMediaSource.wrap(delegate.createMediaSource(mediaItem), mediaItem) { durationMs ->
            onFullDuration(mediaItem.mediaId, durationMs)
        }

    private class LoopaResolver(
        private val resolvers: ResolverRegistry,
    ) : ResolvingDataSource.Resolver {

        override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
            val link = PlayUri.toLink(dataSpec.uri) ?: return dataSpec
            val stream = try {
                // Wolane na watku ladowania ExoPlayera - blokowanie jest tu poprawne.
                resolvers.streamBlocking(link)
            } catch (e: ResolveException) {
                throw IOException(e.message, e)
            }
            return dataSpec.buildUpon()
                .setUri(android.net.Uri.parse(stream.url))
                .setHttpRequestHeaders(stream.headers)
                // Klucz cache zyje tyle, co jeden rozwiazany adres. Ten sam film po
                // ponownym rozwiazaniu moze przyjsc w innym wariancie (inna
                // rozdzielczosc, inny plik na serwerze) - sklejenie bajtow z dwoch
                // roznych plikow daloby zepsute wideo.
                .setKey("${link.trackId}@${stream.expiresAt}")
                .build()
        }
    }
}
