package com.loopa.app.download

import android.util.Log
import com.loopa.app.data.LibraryRepository
import com.loopa.app.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sprzata powiazania z pobranymi plikami przy starcie apki.
 *
 * Dwa przypadki, w ktorych film w apce przestaje byc wierna kopia zrodla:
 *
 *  1. Kopia obejmuje tylko fragment. Wczesniejsza wersja pozwalala takiej
 *     kopii zastapic strumien, wiec wyciecie minuty z trzygodzinnego nagrania
 *     skracalo ten film takze w samej apce. Zrywamy powiazanie - fragment
 *     zostaje w galerii, a apka wraca do pelnego zrodla.
 *  2. Plik zniknal z galerii, bo uzytkownik go skasowal. Apka nadal myslala,
 *     ze film jest pobrany, i probowala odtwarzac nieistniejacy plik.
 *
 * Skan jest tani: dotyka tylko filmow, ktore w ogole maja zapisana kopie.
 */
class LibraryRepair(
    private val repository: LibraryRepository,
    private val gallery: GalleryStore,
) {

    data class Result(val partialUnlinked: Int, val missingUnlinked: Int) {
        val total: Int get() = partialUnlinked + missingUnlinked
    }

    suspend fun run(): Result = withContext(Dispatchers.IO) {
        var partial = 0
        var missing = 0

        val candidates: List<Track> = runCatching { repository.downloadedTracks() }.getOrDefault(emptyList())

        for (track in candidates) {
            val uri = track.localUri ?: continue

            if (!gallery.exists(uri)) {
                repository.forgetLocalCopy(track.id)
                missing++
                continue
            }

            if (!track.hasFullLocalCopy) {
                repository.forgetLocalCopy(track.id)
                partial++
            }
        }

        if (partial + missing > 0) {
            Log.i(TAG, "Naprawiono powiazania: $partial niepelnych, $missing nieistniejacych")
        }
        Result(partial, missing)
    }

    private companion object {
        const val TAG = "LibraryRepair"
    }
}
