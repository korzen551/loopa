package com.loopa.app.media

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.loopa.app.LoopaApp
import com.loopa.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Odtwarzanie zyje tutaj, a nie w ekranie.
 *
 * Dzieki temu dzwiek leci dalej, gdy zgasisz ekran albo wyjdziesz z apki - tak jak
 * w odtwarzaczu muzyki. System pokazuje sterowanie na ekranie blokady i w
 * powiadomieniach; dopiero Ty decydujesz, czy zatrzymac. Obraz odlacza sie sam,
 * gdy nie ma na czym rysowac - dzwieku to nie rusza.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var loopController: LoopController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val durationsSaved: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun onCreate() {
        super.onCreate()
        val app = application as LoopaApp

        // Nizszy prog startowy = film rusza szybciej po przewinieciu. Bufor liczy sie
        // w sekundach materialu, a nie w procentach pliku, wiec 12-godzinne nagranie
        // startuje dokladnie tak samo szybko jak 20-sekundowy klip.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 800,
                /* bufferForPlaybackAfterRebufferMs = */ 2_000,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val mediaSourceFactory = LoopaMediaSourceFactory(
            context = this,
            resolvers = app.resolvers,
            cache = app.mediaCache,
            // TikTok nie podaje dlugosci w metadanych, a z wlasnym koncem segmentu
            // odtwarzacz widzi juz tylko dlugosc do tego konca - prawdziwa dlugosc
            // zapisujemy od razu, gdy zrodlo ja pozna.
            onFullDuration = { trackId, durationMs ->
                if (trackId.isNotEmpty() && durationsSaved.add(trackId)) {
                    scope.launch { runCatching { app.repository.fillDuration(trackId, durationMs) } }
                }
            },
        )

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // Trzyma CPU i siec przy zyciu, gdy ekran jest zgaszony.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply { repeatMode = Player.REPEAT_MODE_OFF }

        loopController = LoopController(player)
        player.addListener(PrefetchListener(app))

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(LoopaSessionCallback())
            .setSessionActivity(openAppIntent())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    /**
     * Zmiecenie apki z listy ostatnich nie ma przerywac odtwarzania - tak samo jak
     * w odtwarzaczu muzyki. Gdy nic nie leci, nie ma po co trzymac serwisu.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        loopController.release()
        mediaSession.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Podgrzewa adresy sasiednich klipow, gdy tylko biezacy ruszy.
     *
     * Bez tego kazde przewiniecie czekalo na pelna ekstrakcje z YouTube - kilka
     * zapytan HTTP plus odszyfrowanie podpisu. Z tym adres zwykle lezy juz gotowy
     * w cache, wiec czas startu nie zalezy od tego, ile filmow ma playlista.
     * Feed przewija w obie strony, wiec grzejemy po jednym w kazda.
     */
    private class PrefetchListener(private val app: LoopaApp) : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = Unit

        override fun onEvents(player: Player, events: Player.Events) {
            if (!events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_TIMELINE_CHANGED,
                )
            ) {
                return
            }
            val index = player.currentMediaItemIndex
            listOf(index + 1, index - 1).forEach { neighbour ->
                if (neighbour < 0 || neighbour >= player.mediaItemCount) return@forEach
                val uri = player.getMediaItemAt(neighbour).localConfiguration?.uri ?: return@forEach
                PlayUri.toLink(uri)?.let { app.resolvers.prefetch(it) }
            }
        }
    }

    private inner class LoopaSessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_SET_LOOP, Bundle.EMPTY))
                .add(SessionCommand(CMD_RESTART_LOOP, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        /**
         * MediaItem-y przyslane przez kontroler nie niosa adresu (Android go obcina
         * po drodze), wiec odtwarzamy go tutaj z requestMetadata.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val restored = mediaItems.map { item ->
                val uri = item.requestMetadata.mediaUri
                if (uri != null) item.buildUpon().setUri(uri).build() else item
            }.toMutableList()
            return Futures.immediateFuture(restored)
        }

        /**
         * Podmiana ustawien petli w locie.
         *
         * Podmieniamy MediaItem po stronie serwisu, a nie kontrolera: tutaj adres
         * zrodla jest kompletny, wiec [Player.replaceMediaItem] widzi, ze zmienily
         * sie same metadane, i aktualizuje wpis bez przerywania dzwieku. Wyjatek to
         * zmiana punktow petli - te siedza w osi czasu zrodla, wiec
         * [LoopSegmentMediaSource] wymusza wtedy nowe zrodlo. Zmiana wraca potem
         * sama do wszystkich kontrolerow.
         */
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_RESTART_LOOP) {
                loopController.restartCurrent()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            if (customCommand.customAction != CMD_SET_LOOP) {
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }

            val index = player.currentMediaItemIndex
            val current = player.currentMediaItem
            if (current == null || index == C.INDEX_UNSET) {
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
            }

            val metadata = current.mediaMetadata.buildUpon()
                .setExtras(Bundle(args))
                .build()
            player.replaceMediaItem(index, current.buildUpon().setMediaMetadata(metadata).build())

            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    companion object {
        /** Ustaw punkty petli dla filmu, ktory wlasnie leci. */
        const val CMD_SET_LOOP = "com.loopa.app.SET_LOOP"

        /** Przestaw film na jego punkt startu i zacznij liczyc powtorzenia od nowa. */
        const val CMD_RESTART_LOOP = "com.loopa.app.RESTART_LOOP"
    }
}
