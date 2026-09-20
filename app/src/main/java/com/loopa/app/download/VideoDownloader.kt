package com.loopa.app.download

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.loopa.app.data.Track
import com.loopa.app.resolve.LinkParser
import com.loopa.app.resolve.Net
import com.loopa.app.resolve.ResolverRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Pobiera film do pliku: przycina do zadanej dlugosci i sprowadza obraz do 9:16.
 *
 * Wazna konsekwencja wymuszania 9:16: obraz musi zostac przekodowany, a nie tylko
 * przepakowany. Dla krotkiego klipu z TikToka to sekundy, ale dla kilkugodzinnego
 * nagrania z YouTube'a potrwa realnie dlugo - to koszt tego, zeby plik na pewno
 * wygladal w galerii jak zwykly pionowy film.
 */
@UnstableApi
class VideoDownloader(
    private val context: Context,
    private val resolvers: ResolverRegistry,
) {

    /**
     * @param startMs ile odciac z poczatku filmu.
     * @param endMs gdzie skonczyc; 0 = do konca filmu.
     * @return plik tymczasowy gotowy do przelania do galerii.
     */
    suspend fun export(
        track: Track,
        startMs: Long,
        endMs: Long,
        onProgress: (Float) -> Unit,
    ): Result<File> = runCatching {
        val link = LinkParser.parse(track.url)
            ?: error("Nie rozpoznaję linku tego filmu")
        val stream = resolvers.stream(link)
            ?: error(resolvers.errorFor(track.id) ?: "Nie udało się pobrać adresu filmu")

        val output = File(context.cacheDir, "downloads").apply { mkdirs() }
            .let { File(it, "loopa-export-${track.id.replace(':', '_')}.mp4") }
        if (output.exists()) output.delete()

        // Zrodlo z naglowkami, ktore wymaga serwer (TikTok bez Referera odmawia).
        val httpFactory = OkHttpDataSource.Factory(Net.client)
            .setUserAgent(Net.DESKTOP_UA)
            .setDefaultRequestProperties(stream.headers)

        val mediaItem = MediaItem.Builder()
            .setUri(stream.url)
            .apply {
                if (startMs > 0L || endMs > 0L) {
                    setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(startMs.coerceAtLeast(0L))
                            .apply { if (endMs > 0L) setEndPositionMs(endMs) }
                            .build()
                    )
                }
            }
            .build()

        val edited = EditedMediaItem.Builder(mediaItem)
            .setEffects(
                Effects(
                    /* audioProcessors = */ emptyList(),
                    /* videoEffects = */ listOf(
                        // Nic nie przycinamy z kadru - pionowe klipy zostaja bez zmian,
                        // poziome dostaja czarne pasy zamiast utraty obrazu.
                        Presentation.createForAspectRatio(
                            9f / 16f,
                            Presentation.LAYOUT_SCALE_TO_FIT,
                        )
                    ),
                )
            )
            .build()

        runExport(edited, httpFactory, output, onProgress)
        output
    }

    private suspend fun runExport(
        edited: EditedMediaItem,
        httpFactory: OkHttpDataSource.Factory,
        output: File,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.Main) {
        val assetLoaderFactory = ExoPlayerAssetLoader.Factory(
            context,
            DefaultDecoderFactory.Builder(context).build(),
            Clock.DEFAULT,
            DefaultMediaSourceFactory(httpFactory),
        )

        var transformer: Transformer? = null
        try {
            suspendCancellableCoroutine { continuation ->
                val built = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setAssetLoaderFactory(assetLoaderFactory)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            result: ExportResult,
                            exception: ExportException,
                        ) {
                            if (continuation.isActive) {
                                continuation.cancel(
                                    IllegalStateException(
                                        exception.message ?: "Przetwarzanie filmu nie powiodło się"
                                    )
                                )
                            }
                        }
                    })
                    .build()

                transformer = built
                continuation.invokeOnCancellation { runCatching { built.cancel() } }
                built.start(edited, output.absolutePath)

                // Postep trzeba odpytywac - Transformer nie zglasza go zdarzeniami.
                val holder = ProgressHolder()
                val poller = object : Runnable {
                    override fun run() {
                        if (!continuation.isActive) return
                        val state = runCatching { built.getProgress(holder) }.getOrNull()
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(holder.progress / 100f)
                        }
                        android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(this, 400)
                    }
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post(poller)
            }
        } finally {
            runCatching { transformer?.cancel() }
        }
    }

    /** Sprzata plik tymczasowy po przelaniu do galerii (albo po nieudanej probie). */
    suspend fun cleanup(file: File) = withContext(Dispatchers.IO) {
        runCatching { if (file.exists()) file.delete() }
        Unit
    }

    /** Odpytywanie postepu wymaga chwili przerwy - pomocnicze dla kolejki. */
    suspend fun settle() = delay(50)
}
