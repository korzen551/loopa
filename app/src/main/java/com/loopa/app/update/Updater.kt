package com.loopa.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.loopa.app.resolve.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Opis nowej wersji. Adres aktualizacji moze wskazywac albo wprost na plik APK,
 * albo na JSON w tej postaci - wtedy jeden staly adres moze przez caly czas
 * pokazywac na kolejne wydania:
 *
 * {"versionCode": 3, "versionName": "1.2", "url": "https://.../loopa.apk"}
 */
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val notes: String? = null,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data class ReadyToInstall(val file: File, val info: UpdateInfo?) : UpdateState
    data class UpToDate(val versionName: String) : UpdateState
    data class Failed(val reason: String) : UpdateState

    /** Android wymaga osobnej zgody na instalowanie z tej aplikacji. */
    data object NeedsInstallPermission : UpdateState
}

/**
 * Pobiera nowa wersje i oddaje ja systemowemu instalatorowi.
 *
 * Aktualizacja "po wierzchu" zachowuje katalog danych aplikacji, wiec playlisty,
 * ustawienia petli i cala baza zostaja nietkniete. Warunek jest jeden: nowy plik
 * musi byc podpisany tym samym kluczem co zainstalowany - inaczej Android odmowi
 * instalacji i trzeba by odinstalowac stara wersje, tracac dane.
 */
class Updater(private val context: Context) {

    /**
     * Rozpoznaje dwa rodzaje adresu:
     *  - wlasny manifest JSON ({"versionCode":…, "versionName":…, "url":…}),
     *  - endpoint wydan GitHuba (…/releases/latest), ktory zwraca wlasny format.
     *
     * Ten drugi jest wygodniejszy: adres wpisujesz raz i nigdy go nie zmieniasz,
     * bo GitHub sam pokazuje najnowsze wydanie.
     */
    suspend fun fetchInfo(url: String): UpdateInfo? = withContext(Dispatchers.IO) {
        val isGitHub = url.contains("api.github.com") && url.contains("/releases/")
        if (!isGitHub && !url.endsWith(".json", ignoreCase = true)) return@withContext null

        runCatching {
            val body = get(url) ?: return@runCatching null
            val json = JSONObject(body)
            if (isGitHub) parseGitHubRelease(json) else parseManifest(json)
        }.getOrNull()
    }

    private fun parseManifest(json: JSONObject) = UpdateInfo(
        versionCode = json.optLong("versionCode", 0L),
        versionName = json.optString("versionName", "?"),
        apkUrl = json.getString("url"),
        notes = json.optString("notes").ifBlank { null },
    )

    private fun parseGitHubRelease(json: JSONObject): UpdateInfo? {
        val assets = json.optJSONArray("assets") ?: return null
        var apkUrl: String? = null
        var manifestUrl: String? = null

        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val download = asset.optString("browser_download_url")
            if (download.isBlank()) continue
            when {
                name.equals("latest.json", ignoreCase = true) -> manifestUrl = download
                name.endsWith(".apk", ignoreCase = true) -> apkUrl = apkUrl ?: download
            }
        }

        // Numer wersji trzymamy w osobnym pliczku dolaczonym do wydania - sama
        // nazwa taga nie wystarcza, bo Android porownuje wersje po liczbie.
        manifestUrl?.let { url ->
            runCatching { parseManifest(JSONObject(get(url).orEmpty())) }
                .getOrNull()
                ?.let { return it }
        }

        val fallback = apkUrl ?: return null
        return UpdateInfo(
            // 0 = nieznana, wiec nie udajemy, ze wiemy, i po prostu pobieramy.
            versionCode = 0L,
            versionName = json.optString("tag_name").ifBlank { "?" },
            apkUrl = fallback,
            notes = json.optString("body").ifBlank { null },
        )
    }

    private fun get(url: String): String? = runCatching {
        val request = Request.Builder().url(url)
            .header("User-Agent", Net.DESKTOP_UA)
            .header("Accept", "application/vnd.github+json")
            .build()
        Net.client.newCall(request).execute().use { it.body?.string() }
    }.getOrNull()

    suspend fun download(url: String, onProgress: (Float) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val target = File(dir, "loopa-update.apk")
                if (target.exists()) target.delete()

                val request = Request.Builder().url(url).header("User-Agent", Net.DESKTOP_UA).build()
                Net.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Serwer odpowiedział ${response.code}")
                    val body = response.body ?: error("Pusta odpowiedź serwera")
                    val total = body.contentLength()

                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var copied = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                copied += read
                                if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }

                // Najtansza sensowna kontrola: APK to archiwum ZIP, wiec zaczyna sie
                // od "PK". Strona bledu albo przekierowanie zapisane jako plik odpadnie
                // tutaj, zamiast wywalic sie dopiero w instalatorze.
                target.inputStream().use { stream ->
                    val magic = ByteArray(2)
                    if (stream.read(magic) != 2 || magic[0] != 'P'.code.toByte() || magic[1] != 'K'.code.toByte()) {
                        target.delete()
                        error("Pobrany plik nie jest APK - sprawdź adres aktualizacji")
                    }
                }

                target
            }
        }

    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun installedVersionCode(): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(0L)

    fun installedVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("?")
}
