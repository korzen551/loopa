package com.loopa.app.media

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.loopa.app.data.LoopSettings
import com.loopa.app.data.RepeatMode

/**
 * Pilnuje wlasnych punktow startu i konca.
 *
 * Sedno: film ma dwa rozne poczatki. [LoopSettings.startMs] dla pierwszego
 * odtworzenia i [LoopSettings.loopStartMs] dla kazdego nastepnego - wiec klip
 * o dlugosci 18 s moze zagrac raz od zera, a potem w kolko od 9. sekundy.
 *
 * Wszystkie punkty przechodza przez [segment], ktory przycina je do rzeczywistej
 * dlugosci filmu. Bez tego punkt ustawiony poza koncem powodowal przewijanie na
 * koniec, natychmiastowe wykrycie konca segmentu i kolejne przewiniecie - czyli
 * ostatni ulamek sekundy odtwarzany setki razy na minute.
 */
@UnstableApi
class LoopController(private val player: Player) : Player.Listener {

    private val handler = Handler(Looper.getMainLooper())

    /** Ile razy biezacy film zostal juz odtworzony w calosci. */
    private var playsDone = 1

    /** Po przewinieciu pozycja przez chwile klamie - nie reaguj w tym czasie. */
    private var quietUntil = 0L

    /**
     * Przewiniecie na punkt startu czeka, az odtwarzacz pozna dlugosc filmu.
     * Wczesniej nie ma do czego przycinac, a samo przewijanie i tak bywa gubione.
     */
    private var pendingStart = false

    private val ticker = object : Runnable {
        override fun run() {
            step()
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        player.addListener(this)
        handler.post(ticker)
        applyRepeatMode()
    }

    fun release() {
        handler.removeCallbacks(ticker)
        player.removeListener(this)
    }

    /** Punkty juz przyciete do tego, co film faktycznie ma. */
    private data class Segment(
        val start: Long,
        val loopStart: Long,
        val end: Long,
        val settings: LoopSettings,
    )

    private fun segment(): Segment? {
        val settings = player.currentMediaItem.loopSettings()
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: return null

        var end = settings.endMs.takeIf { it > 0L }?.coerceAtMost(duration) ?: duration
        val ceiling = (duration - MIN_SEGMENT_MS).coerceAtLeast(0L)
        var start = settings.startMs.coerceIn(0L, ceiling)
        var loopStart = settings.loopStartMs.coerceIn(0L, ceiling)

        // Zdegenerowany segment (koniec przed poczatkiem albo tuz za nim) zamiast
        // grac ulamek sekundy w kolko wraca po prostu na poczatek filmu.
        if (end - loopStart < MIN_SEGMENT_MS) loopStart = 0L
        if (end - start < MIN_SEGMENT_MS) start = 0L
        if (end - loopStart < MIN_SEGMENT_MS) end = duration

        return Segment(start, loopStart, end, settings)
    }

    /**
     * Ustawia film na jego punkcie startu i zaczyna liczyc powtorzenia od nowa.
     * Wolane po zapisaniu ustawien, zeby zmiana byla slychac od razu.
     */
    fun restartCurrent() {
        playsDone = 1
        applyRepeatMode()
        val seg = segment()
        if (seg == null) {
            pendingStart = true
            return
        }
        seekQuietly(seg.start)
        player.play()
    }

    // ---------- reakcje na gracza ----------

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return // patrz onPositionDiscontinuity
        playsDone = 1
        applyRepeatMode()
        pendingStart = mediaItem.loopSettings().startMs > 0L
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) return
        // Ten sam indeks = film zapetlil sie sam (REPEAT_MODE_ONE) i stoi na zerze.
        if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex) return
        segment()?.let { onSegmentFinished(it) }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_READY -> if (pendingStart) {
                val seg = segment() ?: return
                pendingStart = false
                if (seg.start > 0L) seekQuietly(seg.start)
            }

            Player.STATE_ENDED -> {
                val seg = segment() ?: return
                if (seg.settings.loops && repeatsLeft(seg.settings)) {
                    playsDone++
                    seekQuietly(seg.loopStart)
                    player.play()
                }
            }
        }
    }

    // ---------- odpytywanie ----------

    private fun step() {
        if (!player.isPlaying) return
        if (SystemClock.elapsedRealtime() < quietUntil) return
        val seg = segment() ?: return
        if (player.currentPosition >= seg.end - LEAD_MS) onSegmentFinished(seg)
    }

    private fun onSegmentFinished(seg: Segment) {
        if (SystemClock.elapsedRealtime() < quietUntil) return

        if (seg.settings.loops && repeatsLeft(seg.settings)) {
            playsDone++
            seekQuietly(seg.loopStart)
            player.play()
            return
        }

        applyRepeatMode() // zdejmij REPEAT_MODE_ONE, skoro powtorki sie skonczyly

        when {
            player.hasNextMediaItem() -> {
                player.seekToNextMediaItem()
                player.play()
            }
            // Wlasny koniec segmentu na ostatnim filmie: zatrzymaj sie tutaj,
            // zamiast dogrywac reszte, ktorej uzytkownik nie chcial.
            seg.settings.endMs > 0L -> {
                player.pause()
                seekQuietly(seg.start)
            }
        }
    }

    private fun repeatsLeft(settings: LoopSettings): Boolean = when (settings.repeatMode) {
        RepeatMode.OFF -> false
        RepeatMode.LOOP -> true
        RepeatMode.COUNT -> playsDone < settings.repeatCount.coerceAtLeast(1)
    }

    /**
     * REPEAT_MODE_ONE jest siatka bezpieczenstwa: trzyma film na tej samej pozycji
     * w kolejce przy naturalnym koncu, zeby zdazyc przewinac na wlasny punkt startu
     * petli, zamiast dac ExoPlayerowi przeskoczyc dalej.
     */
    private fun applyRepeatMode() {
        val settings = player.currentMediaItem.loopSettings()
        val wanted = if (settings.loops && repeatsLeft(settings)) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
        if (player.repeatMode != wanted) player.repeatMode = wanted
    }

    private fun seekQuietly(positionMs: Long) {
        quietUntil = SystemClock.elapsedRealtime() + QUIET_MS
        player.seekTo(positionMs.coerceAtLeast(0L))
    }

    private companion object {
        const val TICK_MS = 60L

        /** Zapas, zeby przewinac zanim zrodlo dojedzie do konca. */
        const val LEAD_MS = 150L

        /** Cisza po przewinieciu - inaczej jeden skok wywolalby lawine. */
        const val QUIET_MS = 450L

        /** Krotszego kawalka nie ma sensu zapetlac. */
        const val MIN_SEGMENT_MS = 600L
    }
}
