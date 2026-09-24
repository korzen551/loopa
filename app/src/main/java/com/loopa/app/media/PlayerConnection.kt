package com.loopa.app.media

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Jedno polaczenie z [PlaybackService] na cala apke. Ekrany przychodza i odchodza,
 * odtwarzanie zostaje.
 */
@UnstableApi
object PlayerConnection {

    private var future: ListenableFuture<MediaController>? = null

    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = _controller.asStateFlow()

    fun connect(context: Context) {
        if (future != null) return
        val token = SessionToken(
            context.applicationContext,
            ComponentName(context.applicationContext, PlaybackService::class.java),
        )
        future = MediaController.Builder(context.applicationContext, token).buildAsync().also { pending ->
            pending.addListener(
                { _controller.value = runCatching { pending.get() }.getOrNull() },
                MoreExecutors.directExecutor(),
            )
        }
    }

    fun release() {
        future?.let { MediaController.releaseFuture(it) }
        future = null
        _controller.value = null
    }

    /**
     * Wrzuca kolejke i startuje od [startIndex]. Pierwsze odtworzenie rusza z
     * wlasnego punktu startu, a nie od zera.
     */
    fun play(items: List<PlayableItem>, startIndex: Int = 0) {
        val controller = _controller.value ?: return
        if (items.isEmpty()) return
        val index = startIndex.coerceIn(0, items.lastIndex)
        controller.setMediaItems(
            items.map { it.toMediaItem() },
            index,
            items[index].loop.startMs.coerceAtLeast(0L),
        )
        controller.prepare()
        controller.play()
    }

    fun playSingle(item: PlayableItem) = play(listOf(item), 0)

    /**
     * Podmienia ustawienia petli dla filmu, ktory wlasnie leci - bez przerywania
     * dzwieku. Uzywane, gdy suwaki w edytorze ida na zywo.
     *
     * Idzie to wlasnym poleceniem sesji, a nie podmiana pozycji z tej strony:
     * kontroler nie ma kompletnego adresu zrodla, wiec podmiana stad zgubilaby go
     * po drodze. Serwis ma go w calosci i podmienia u siebie.
     */
    fun updateCurrentLoop(item: PlayableItem) {
        val controller = _controller.value ?: return
        if (controller.mediaItemCount == 0) return
        controller.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_SET_LOOP, Bundle.EMPTY),
            item.loopBundle(),
        )
    }

    /** Przestawia biezacy film na jego punkt startu - po zatwierdzeniu ustawien. */
    fun restartCurrentLoop() {
        val controller = _controller.value ?: return
        if (controller.mediaItemCount == 0) return
        controller.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_RESTART_LOOP, Bundle.EMPTY),
            Bundle.EMPTY,
        )
    }

    /** Przeskok do konkretnej pozycji w kolejce - uzywany przez przewijanie palcem. */
    fun goToIndex(index: Int, startPositionMs: Long = 0L) {
        val controller = _controller.value ?: return
        if (index < 0 || index >= controller.mediaItemCount) return
        if (controller.currentMediaItemIndex == index) return
        controller.seekTo(index, startPositionMs.coerceAtLeast(0L))
        controller.play()
    }

    /**
     * Predkosc odtwarzania - nie jest zapisywana do bazy, dziala tylko w biezacej
     * sesji, tak jak w wiekszosci odtwarzaczy wideo. Zakres 0,1-10x jest szerszy
     * niz typowy suwak "0,5-2x" celowo - to co uzytkownik chce, to jego sprawa.
     */
    fun setPlaybackSpeed(speed: Float) {
        _controller.value?.setPlaybackSpeed(speed.coerceIn(0.1f, 10f))
    }

    /** Aktualna predkosc - punkt startowy dla suwaka i to, do czego wraca "przytrzymaj, by 2x". */
    fun currentSpeed(): Float = _controller.value?.playbackParameters?.speed ?: 1f

    fun togglePlayPause() {
        val controller = _controller.value ?: return
        if (controller.isPlaying) controller.pause() else controller.play()
    }

    fun stop() {
        _controller.value?.run {
            pause()
            clearMediaItems()
        }
    }
}
