package com.loopa.app

import android.app.Application
import android.content.Context
import com.loopa.app.data.AppDatabase
import com.loopa.app.data.LibraryRepository
import com.loopa.app.resolve.Net
import com.loopa.app.resolve.NewPipeDownloader
import com.loopa.app.resolve.ResolverRegistry
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

    override fun onCreate() {
        super.onCreate()
        NewPipe.init(
            NewPipeDownloader(Net.client),
            Localization("pl", "PL"),
            ContentCountry("PL"),
        )
    }
}

val Context.loopa: LoopaApp get() = applicationContext as LoopaApp
