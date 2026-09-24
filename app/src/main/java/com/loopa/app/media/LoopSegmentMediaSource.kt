package com.loopa.app.media

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.WrappingMediaSource
import com.loopa.app.data.RepeatMode

/**
 * Petla zapisana w osi czasu ExoPlayera, a nie robiona przewijaniem.
 *
 * Przewiniecie (seekTo) zawsze czysci dekodery obrazu i dzwieku, a przy
 * strumieniu dodatkowo czeka na bufor - stad ulamek sekundy ciszy i zamrozonego
 * obrazu przy kazdym powtorzeniu. Natomiast w REPEAT_MODE_ONE ExoPlayer laduje
 * kolejne przejscie przez film z wyprzedzeniem i przechodzi do niego bez
 * zatrzymywania dekoderow, jak miedzy utworami na plycie bez przerw.
 *
 * Zeby to natywne powtorzenie trafialo w nasze punkty:
 *  - koniec segmentu jest koncem okresu ([ClippingMediaSource]),
 *  - punkt, od ktorego rusza kazde kolejne przejscie, jest domyslna pozycja okna -
 *    od niej ExoPlayer zaczyna kazde wejscie w ten film, takze powtorzenie.
 */
@UnstableApi
class LoopSegmentMediaSource private constructor(
    child: MediaSource,
    private val points: SegmentPoints,
) : WrappingMediaSource(child) {

    override fun onChildSourceInfoRefreshed(newTimeline: Timeline) {
        refreshSourceInfo(
            if (points.entryMs > 0L) EntryTimeline(newTimeline, Util.msToUs(points.entryMs)) else newTimeline
        )
    }

    /**
     * Sama zmiana metadanych (np. trybu powtarzania) podmienia wpis bez przerywania
     * dzwieku. Zmiana punktow wymaga nowego zrodla - biezacy okres gralby dalej
     * ze starym koncem.
     */
    override fun canUpdateMediaItem(mediaItem: MediaItem): Boolean =
        SegmentPoints.of(mediaItem) == points && super.canUpdateMediaItem(mediaItem)

    private class EntryTimeline(timeline: Timeline, private val entryUs: Long) : ForwardingTimeline(timeline) {
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
            super.getWindow(windowIndex, window, defaultPositionProjectionUs)
            // Dopoki dlugosc nie jest znana, nie da sie sprawdzic, czy punkt w ogole
            // lezy w filmie. Punkt tuz przed koncem albo za nim zapetlalby w kolko
            // ostatni ulamek sekundy - wtedy zostajemy przy poczatku.
            val durationUs = window.durationUs
            if (durationUs != C.TIME_UNSET && entryUs <= durationUs - Util.msToUs(MIN_SEGMENT_MS)) {
                window.defaultPositionUs = entryUs
            }
            return window
        }
    }

    /** Zglasza dlugosc calego filmu, zanim przyciecie konca ja ukryje. */
    private class DurationProbe(
        child: MediaSource,
        private val onFullDuration: (Long) -> Unit,
    ) : WrappingMediaSource(child) {
        private val window = Timeline.Window()

        override fun onChildSourceInfoRefreshed(newTimeline: Timeline) {
            if (!newTimeline.isEmpty) {
                newTimeline.getWindow(0, window)
                if (!window.isPlaceholder && window.durationUs != C.TIME_UNSET && window.durationUs > 0L) {
                    onFullDuration(Util.usToMs(window.durationUs))
                }
            }
            refreshSourceInfo(newTimeline)
        }
    }

    /**
     * To, co z ustawien petli dotyczy samego zrodla.
     *
     * [entryMs] to miejsce, od ktorego film rusza, gdy wchodzi sie w niego bez
     * podanej pozycji - przede wszystkim przy kazdym powtorzeniu. Gdy film sie nie
     * powtarza, jedynym takim wejsciem jest pierwsze odtworzenie, wiec wtedy to
     * po prostu jego start.
     */
    internal data class SegmentPoints(val entryMs: Long, val endMs: Long) {
        companion object {
            fun of(item: MediaItem): SegmentPoints {
                val loop = item.loopSettings()
                var entry = (if (loop.repeatMode == RepeatMode.OFF) loop.startMs else loop.loopStartMs)
                    .coerceAtLeast(0L)
                var end = loop.endMs.coerceAtLeast(0L)

                // Zdegenerowany segment zamiast grac ulamek sekundy w kolko wraca na
                // poczatek filmu, a koniec krotszy niz minimum po prostu znika.
                if (end > 0L && end - entry < MIN_SEGMENT_MS) entry = 0L
                if (end in 1L until MIN_SEGMENT_MS) end = 0L
                return SegmentPoints(entry, end)
            }
        }
    }

    companion object {
        /** Krotszego kawalka nie ma sensu zapetlac. */
        const val MIN_SEGMENT_MS = 600L

        fun wrap(
            source: MediaSource,
            mediaItem: MediaItem,
            onFullDuration: (Long) -> Unit,
        ): MediaSource {
            val points = SegmentPoints.of(mediaItem)
            var inner: MediaSource = DurationProbe(source, onFullDuration)
            if (points.endMs > 0L) {
                inner = ClippingMediaSource(
                    inner,
                    /* startPositionUs = */ 0L,
                    /* endPositionUs = */ Util.msToUs(points.endMs),
                    /* enableInitialDiscontinuity = */ false,
                    /* allowDynamicClippingUpdates = */ false,
                    /* relativeToDefaultPosition = */ false,
                )
            }
            return LoopSegmentMediaSource(inner, points)
        }
    }
}
