package com.loopa.app.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Album w galerii.
 *
 * Wazne, bo to zaskakuje: w Androidzie "album" widziany w galerii to po prostu
 * FOLDER na dysku. Nie ma publicznego sposobu, zeby dopisac plik do albumu
 * wirtualnego (takiego, ktory Samsung Gallery trzyma u siebie w bazie), i jeden
 * plik nie moze lezec w dwoch folderach naraz. Dlatego wybor albumu jest
 * pojedynczy, a nie wielokrotny.
 */
data class GalleryAlbum(
    val name: String,
    /** Sciezka wzgledem pamieci wspoldzielonej, np. "Movies/Loopa/". */
    val relativePath: String,
    val videoCount: Int,
) {
    companion object {
        /** Domyslne miejsce, gdy uzytkownik nic nie wybierze. */
        val Default = GalleryAlbum(
            name = "Loopa",
            relativePath = "${Environment.DIRECTORY_MOVIES}/Loopa/",
            videoCount = 0,
        )
    }
}

/**
 * Zapis filmow do galerii telefonu przez MediaStore.
 *
 * Od Androida 10 nie trzeba zadnych uprawnien, zeby dopisac wlasny plik do
 * galerii - wystarczy MediaStore. Na starszych systemach potrzebny jest zapis
 * do pamieci zewnetrznej, stad rozgalezienie.
 */
class GalleryStore(private val context: Context) {

    private val resolver get() = context.contentResolver
    private val collection: Uri
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

    /** Foldery, w ktorych juz leza jakies filmy - to sa "albumy" w galerii. */
    suspend fun albums(): List<GalleryAlbum> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext listOf(GalleryAlbum.Default)

        val counts = LinkedHashMap<String, Pair<String, Int>>()
        val projection = arrayOf(
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.RELATIVE_PATH,
        )
        runCatching {
            resolver.query(collection, projection, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")
                ?.use { cursor ->
                    val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                    val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
                    while (cursor.moveToNext()) {
                        val path = cursor.getString(pathCol) ?: continue
                        val name = cursor.getString(bucketCol) ?: path.trim('/').substringAfterLast('/')
                        val current = counts[path]
                        counts[path] = (current?.first ?: name) to ((current?.second ?: 0) + 1)
                    }
                }
        }

        val found = counts.map { (path, value) ->
            GalleryAlbum(name = value.first, relativePath = path, videoCount = value.second)
        }
        // Wlasny folder zawsze na gorze, nawet gdy jeszcze nie istnieje.
        val withDefault = if (found.none { it.relativePath == GalleryAlbum.Default.relativePath }) {
            listOf(GalleryAlbum.Default) + found
        } else {
            found.sortedByDescending { it.relativePath == GalleryAlbum.Default.relativePath }
        }
        withDefault
    }

    /**
     * Tworzy wpis w galerii i oddaje jego adres. Plik jest oznaczony jako
     * "w trakcie zapisu", wiec galeria nie pokaze go, dopoki nie wywolasz
     * [publish] - dzieki temu przerwane pobieranie nie zostawia smiecia.
     */
    suspend fun createPending(displayName: String, album: GalleryAlbum): Uri? =
        withContext(Dispatchers.IO) {
            val safeName = displayName.replace(Regex("""[\\/:*?"<>|]"""), "_").take(120)
            val fileName = if (safeName.endsWith(".mp4", true)) safeName else "$safeName.mp4"

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, album.relativePath)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                        "Loopa",
                    ).apply { mkdirs() }
                    @Suppress("DEPRECATION")
                    put(MediaStore.Video.Media.DATA, File(dir, fileName).absolutePath)
                }
            }
            runCatching { resolver.insert(collection, values) }.getOrNull()
        }

    /** Przelewa gotowy plik do wpisu w galerii i odslania go. */
    suspend fun writeAndPublish(target: Uri, source: File, durationMs: Long): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                resolver.openOutputStream(target, "w")?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output, 256 * 1024) }
                } ?: error("Galeria nie dała się otworzyć do zapisu")

                val done = ContentValues().apply {
                    put(MediaStore.Video.Media.DURATION, durationMs)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                }
                resolver.update(target, done, null, null)
                true
            }.getOrElse {
                discard(target)
                false
            }
        }

    /** Kasuje niedokonczony wpis, zeby nie zostawiac uszkodzonego pliku w galerii. */
    suspend fun discard(uri: Uri) = withContext(Dispatchers.IO) {
        runCatching { resolver.delete(uri, null, null) }
        Unit
    }

    /** Czy plik nadal istnieje - uzytkownik mogl go skasowac z galerii. */
    suspend fun exists(uri: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            resolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
        }.getOrDefault(false)
    }
}
