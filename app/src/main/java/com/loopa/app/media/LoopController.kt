package com.loopa.app.media

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.loopa.app.data.LoopSettings
import com.loopa.app.data.RepeatMode
import kotlin.math.abs

/**
 * Pilnuje ile razy film ma polecic i gdzie zaczyna sie jego pierwsze odtworzenie.
 *
 * Samego zapetlania juz tu nie ma: koniec segmentu i punkt powrotu siedza w osi
 * czasu ([LoopSegmentMediaSource]), a powtorzenie robi ExoPlayer w
 * REPEAT_MODE_ONE, bez przewijania. Wczesniej ten kontroler odpytywal pozycje
 * i przewijal tuz przed koncem - a kazde przewiniecie to wyczyszczone dekodery
 * i (przy strumieniu) czekanie na bufor, czyli slyszalna i widoczna dziura
 * przy kazdym powtorzeniu.
 *
 * Film ma dwa rozne poczatki: [LoopSettings.startMs] dla pierwszego odtworzenia
 * i [LoopSettings.loopStartMs] dla kazdego nastepnego - klip o dlugosci 18 s
 * moze zagrac raz od zera, a potem w kolko od 9. sekundy.
 */
@UnstableApi
class LoopController(private val player: Player) : Player.Listener {

    /** Ile razy biezacy film zostal juz odtworzony w calosci. */
    private var playsDone = 1

    /**
     * Swiezo wybrany film trzeba jeszcze postawic na jego punkcie startu. Czeka to
     * na znana dlugosc filmu - wczesniej nie ma do czego przyciac punktu.
     */
    private var pendingStart = false

    init {
        player.addListener(this)
        applyRepeatMode()
    }

    fun release() {
        player.removeListener(this)
    }

    /**
     * Ustawia film na jego punkt startu i zaczyna liczyc powtorzenia od nowa.
     * Wolane po zapisaniu ustawien, zeby zmiana byla slychac od razu.
     */
    fun restartCurrent() {
        playsDone = 1
        applyRepeatMode()
        pendingStart = false
        val start = firstStart()
        if (start == null) {
            pendingStart = true
        } else {
            player.seekTo(start)
        }
        player.play()
    }

    // ---------- reakcje na gracza ----------

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return // patrz onPositionDiscontinuity
        playsDone = 1
        applyRepeatMode()
        pendingStart = true
        applyPendingStart()
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) return
        // Ten sam indeks = film sam zaczal kolejne przejscie. ExoPlayer stoi juz na
        // punkcie powrotu, nic tu nie przewijamy - tylko liczymy.
        if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex) return
        playsDone++
        applyRepeatMode()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_READY -> applyPendingStart()
            Player.STATE_ENDED -> onQueueEnded()
        }
    }

    // ---------- logika ----------

    /**
     * Wejscie w film bez podanej pozycji (automatyczne przejscie dalej, "nastepny"
     * z powiadomienia) laduje na punkcie powrotu petli. Pierwsze odtworzenie ma
     * jednak swoj wlasny start - tu go dopinamy, jednym przewinieciem na samym
     * poczatku filmu. Gdy film juz stoi tam, gdzie trzeba (np. przewiniety palcem
     * w feedzie z podana pozycja), nic nie robimy.
     */
    private fun applyPendingStart() {
        if (!pendingStart) return
        val start = firstStart() ?: return
        pendingStart = false
        if (abs(player.currentPosition - start) > START_TOLERANCE_MS) player.seekTo(start)
    }

    /**
     * Ostatni film w kolejce sie skonczyl. Stajemy na jego starcie w pauzie, zeby
     * "graj" od razu dzialalo - w stanie ENDED ExoPlayer ignoruje samo play().
     */
    private fun onQueueEnded() {
        if (player.mediaItemCount == 0) return // wyczyszczona kolejka tez konczy sie stanem ENDED
        val settings = player.currentMediaItem.loopSettings()
        if (settings.loops && repeatsLeft(settings)) {
            // Siatka bezpieczenstwa: tryb powtarzania nie zdazyl wejsc.
            applyRepeatMode()
            player.seekToDefaultPosition()
            player.play()
            return
        }
        player.pause()
        firstStart()?.let { player.seekTo(it) } ?: player.seekToDefaultPosition()
    }

    /** Start pierwszego odtworzenia przyciety do filmu; null, gdy dlugosc nieznana. */
    private fun firstStart(): Long? {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: return null
        val start = player.currentMediaItem.loopSettings().startMs.coerceAtLeast(0L)
        // Start tuz przed koncem albo za nim gralby ulamek sekundy - wtedy od zera.
        return if (start > duration - LoopSegmentMediaSource.MIN_SEGMENT_MS) 0L else start
    }

    private fun repeatsLeft(settings: LoopSettings): Boolean = when (settings.repeatMode) {
        RepeatMode.OFF -> false
        RepeatMode.LOOP -> true
        RepeatMode.COUNT -> playsDone < settings.repeatCount.coerceAtLeast(1)
    }

    /**
     * REPEAT_MODE_ONE to teraz sam mechanizm petli: ExoPlayer z wyprzedzeniem
     * laduje kolejne przejscie przez ten film. Gdy powtorzenia sie skoncza,
     * przelaczamy na OFF jeszcze w trakcie ostatniego przejscia - wtedy z tym
     * samym wyprzedzeniem laduje sie nastepny film i przejscie do niego tez jest
     * bez przerwy.
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

    private companion object {
        /** Mniejsza roznica nie jest warta przewiniecia (ktore samo daje przerwe). */
        const val START_TOLERANCE_MS = 250L
    }
}
