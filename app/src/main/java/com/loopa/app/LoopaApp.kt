package com.loopa.app

import android.app.Application
import android.content.Context
import com.loopa.app.data.AppDatabase
import com.loopa.app.data.LibraryRepository
import com.loopa.app.resolve.Net
import com.loopa.app.resolve.NewPipeDownloader
import com.loopa.app.resolve.ResolverRegistry
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class LoopaApp : Application() {

    val resolvers: ResolverRegistry by lazy { ResolverRegistry() }

    val repository: LibraryRepository by lazy {
        LibraryRepository(AppDatabase.get(this), resolvers)
    }

    /** Kolejka pobierania do galerii - zyje w skali apki, nie ekranu. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val downloads: com.loopa.app.download.DownloadQueue by lazy {
        com.loopa.app.download.DownloadQueue(this, repository, resolvers)
    }

    val gallery: com.loopa.app.download.GalleryStore by lazy {
        com.loopa.app.download.GalleryStore(this)
    }

    /**
     * Cache pobranych kawalkow strumieni. Jedna instancja na proces - SimpleCache
     * zaklada blokade na katalog i druga instancja na tym samym katalogu sie wywali,
     * a serwis odtwarzania moze byc tworzony wiele razy w zyciu procesu.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val mediaCache: androidx.media3.datasource.cache.SimpleCache by lazy {
        androidx.media3.datasource.cache.SimpleCache(
            java.io.File(cacheDir, "media"),
            androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(MEDIA_CACHE_BYTES),
            androidx.media3.database.StandaloneDatabaseProvider(this),
        )
    }

    override fun onCreate() {
        super.onCreate()
        NewPipe.init(
            NewPipeDownloader(Net.client),
            Localization("pl", "PL"),
            ContentCountry("PL"),
        )

        // Po starcie sprzatamy powiazania z pobranymi plikami: niepelne kopie
        // przestaja zastepowac zrodlo, a kopie skasowane z galerii przestaja
        // udawac, ze nadal sa. Naprawia to takze filmy zepsute przez
        // wczesniejsza wersje.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching {
                com.loopa.app.download.LibraryRepair(repository, gallery).run()
            }
        }
    }

    private companion object {
        /** Kilkaset klipow z TikToka; system i tak czysci cache, gdy brakuje miejsca. */
        const val MEDIA_CACHE_BYTES = 300L * 1024 * 1024
    }
}

val Context.loopa: LoopaApp get() = applicationContext as LoopaApp
