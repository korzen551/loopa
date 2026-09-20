package com.loopa.app.download

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.loopa.app.data.LibraryRepository
import com.loopa.app.resolve.ResolverRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Jedno zadanie: film + ile z niego pobrac + gdzie w galerii ma wyladowac. */
data class DownloadJob(
    val trackId: String,
    val title: String,
    /** 0 = caly film. */
    val limitMs: Long,
    val album: GalleryAlbum,
)

sealed interface DownloadState {
    data object Idle : DownloadState

    data class Running(
        val title: String,
        val progress: Float,
        val done: Int,
        val total: Int,
    ) : DownloadState

    data class Finished(
        val saved: Int,
        val failed: Int,
        val firstError: String?,
    ) : DownloadState
}

/**
 * Pobiera filmy po kolei, nie rownolegle.
 *
 * Przekodowanie obrazu zjada caly sprzetowy koder - dwa naraz albo by sie
 * wywrocily, albo chodzilyby wolniej niz jeden po drugim. Kolejka zyje w skali
 * aplikacji, wiec zejscie z ekranu nie przerywa zapisu; zamkniecie apki owszem.
 */
@UnstableApi
class DownloadQueue(
    context: Context,
    private val repository: LibraryRepository,
    resolvers: ResolverRegistry,
) {
    private val gallery = GalleryStore(context)
    private val downloader = VideoDownloader(context, resolvers)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    private var worker: Job? = null

    val isBusy: Boolean get() = _state.value is DownloadState.Running

    fun enqueue(jobs: List<DownloadJob>) {
        if (jobs.isEmpty() || isBusy) return
        worker = scope.launch { run(jobs) }
    }

    suspend fun cancel() {
        worker?.cancelAndJoin()
        worker = null
        _state.value = DownloadState.Idle
    }

    fun acknowledge() {
        if (_state.value is DownloadState.Finished) _state.value = DownloadState.Idle
    }

    private suspend fun run(jobs: List<DownloadJob>) {
        var saved = 0
        var failed = 0
        var firstError: String? = null

        jobs.forEachIndexed { index, job ->
            _state.value = DownloadState.Running(job.title, 0f, index, jobs.size)

            val outcome = runCatching { process(job) { progress ->
                _state.value = DownloadState.Running(job.title, progress, index, jobs.size)
            } }

            outcome.fold(
                onSuccess = { saved++ },
                onFailure = { error ->
                    failed++
                    if (firstError == null) {
                        firstError = "${job.title}: ${error.message ?: "nieznany błąd"}"
                    }
                },
            )
            downloader.settle()
        }

        _state.value = DownloadState.Finished(saved, failed, firstError)
    }

    private suspend fun process(job: DownloadJob, onProgress: (Float) -> Unit) {
        val track = repository.track(job.trackId) ?: error("Film zniknął z biblioteki")

        val file = downloader.export(track, job.limitMs, onProgress).getOrThrow()
        try {
            val target = gallery.createPending(track.title.ifBlank { track.id }, job.album)
                ?: error("Galeria odmówiła utworzenia pliku")

            val effectiveDuration = if (job.limitMs > 0L) job.limitMs else track.durationMs
            val published = gallery.writeAndPublish(target, file, effectiveDuration)
            if (!published) error("Nie udało się zapisać pliku w galerii")

            repository.setLocalCopy(track.id, target.toString(), effectiveDuration)
        } finally {
            downloader.cleanup(file)
        }
    }
}
