package com.loopa.app.media

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
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
 * Zamienia adresy `loopa://play?...` na prawdziwe adresy strumieni.
 *
 * Rozwiazywanie dzieje sie leniwie, przy KAZDYM otwarciu zrodla (takze po
 * przewinieciu i po wznowieniu), wiec gdy podpisany adres wygasnie w trakcie
 * grania, ExoPlayer po prostu dostaje swiezy przy ponownym otwarciu. Nic nie
 * laduje na dysku - to czysty streaming.
 */
@UnstableApi
class LoopaMediaSourceFactory(
    private val resolvers: ResolverRegistry,
) : MediaSource.Factory {

    private val httpFactory = OkHttpDataSource.Factory(Net.client)
        .setUserAgent(Net.DESKTOP_UA)

    private val resolvingFactory = ResolvingDataSource.Factory(httpFactory, LoopaResolver(resolvers))

    private val delegate = DefaultMediaSourceFactory(resolvingFactory)

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory =
        apply { delegate.setDrmSessionManagerProvider(provider) }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory =
        apply { delegate.setLoadErrorHandlingPolicy(policy) }

    override fun getSupportedTypes(): IntArray = delegate.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource = delegate.createMediaSource(mediaItem)

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
            return dataSpec
                .withUri(android.net.Uri.parse(stream.url))
                .withRequestHeaders(stream.headers)
        }
    }
}
