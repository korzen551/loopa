package com.loopa.app.data.sync

import com.loopa.app.resolve.Net
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Wspolna biblioteka lezy w jednym pliku w tajnym gist'cie na koncie GitHub
 * uzytkownika. Token ma uprawnienie tylko do gistow - nie do repozytoriow.
 * Obie wersje Loopa (telefon i komputer) szukaja gista po nazwie pliku, wiec
 * wystarczy wkleic ten sam token na obu urzadzeniach.
 */
class GistStore(private val token: String) {

    class AuthException(message: String) : IOException(message)

    /** Gist zostal usuniety na GitHubie - trzeba go znalezc albo zalozyc od nowa. */
    class GistGoneException : IOException("Nie ma już pliku biblioteki na GitHubie.")

    private val json = "application/json; charset=utf-8".toMediaType()

    private fun request(url: String) = Request.Builder().url(url)
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "Loopa")

    private fun call(builder: Request.Builder): String {
        Net.client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when {
                response.code == 401 -> throw AuthException("GitHub nie przyjął tokenu — wklej go jeszcze raz.")
                response.code == 403 -> throw AuthException("Token nie ma uprawnienia „gist”. Utwórz nowy z zaznaczonym „gist”.")
                response.code == 404 -> throw GistGoneException()
                !response.isSuccessful -> throw IOException("GitHub odpowiedział ${response.code}")
            }
            return body
        }
    }

    /** Id gista z biblioteka; tworzy go, gdy jeszcze nie istnieje. */
    fun findOrCreate(): String {
        var page = 1
        while (page <= 10) {
            val list = JSONArray(call(request("https://api.github.com/gists?per_page=100&page=$page")))
            for (i in 0 until list.length()) {
                val gist = list.getJSONObject(i)
                if (gist.optJSONObject("files")?.has(FILE) == true) return gist.getString("id")
            }
            if (list.length() < 100) break
            page++
        }
        val body = JSONObject().apply {
            put("description", "Loopa – wspólna biblioteka (synchronizacja telefonu i komputera)")
            put("public", false)
            put("files", JSONObject().put(FILE, JSONObject().put("content", SyncDoc().toJson())))
        }
        return JSONObject(call(request("https://api.github.com/gists").post(body.toString().toRequestBody(json))))
            .getString("id")
    }

    fun read(gistId: String): SyncDoc {
        val gist = JSONObject(call(request("https://api.github.com/gists/$gistId")))
        val file = gist.optJSONObject("files")?.optJSONObject(FILE) ?: return SyncDoc()
        // Duzy plik GitHub ucina w odpowiedzi - wtedy cala tresc jest pod raw_url.
        val content = if (file.optBoolean("truncated")) {
            call(request(file.getString("raw_url")))
        } else {
            file.optString("content")
        }
        return SyncDoc.fromJson(content)
    }

    fun write(gistId: String, doc: SyncDoc) {
        val body = JSONObject().put("files", JSONObject().put(FILE, JSONObject().put("content", doc.toJson())))
        call(request("https://api.github.com/gists/$gistId").patch(body.toString().toRequestBody(json)))
    }

    companion object {
        const val FILE = "loopa-library.json"
        const val NEW_TOKEN_URL = "https://github.com/settings/tokens/new?scopes=gist&description=Loopa"
    }
}
