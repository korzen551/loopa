package com.loopa.app.resolve

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

object Net {

    const val DESKTOP_UA: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun getString(url: String, headers: Map<String, String> = emptyMap()): Pair<String, okhttp3.Response> {
        val builder = okhttp3.Request.Builder().url(url).header("User-Agent", DESKTOP_UA)
        headers.forEach { (k, v) -> builder.header(k, v) }
        val response = client.newCall(builder.build()).execute()
        val body = response.body?.string().orEmpty()
        return body to response
    }

    /** Podaza za przekierowaniem i zwraca koncowy adres (krotkie linki TikToka). */
    fun finalUrl(url: String): String {
        val request = okhttp3.Request.Builder().url(url).head()
            .header("User-Agent", DESKTOP_UA)
            .build()
        return runCatching {
            client.newCall(request).execute().use { it.request.url.toString() }
        }.getOrElse { url }
    }
}

/** Most miedzy NewPipeExtractor a OkHttp. */
class NewPipeDownloader(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val payload: ByteArray? = request.dataToSend()
        val body = payload?.toRequestBody(null, 0, payload.size)
        val builder = okhttp3.Request.Builder()
            .method(request.httpMethod(), body)
            .url(request.url())
            .addHeader("User-Agent", Net.DESKTOP_UA)

        request.headers().forEach { (name, values) ->
            when {
                values.size > 1 -> {
                    builder.removeHeader(name)
                    values.forEach { builder.addHeader(name, it) }
                }
                values.size == 1 -> builder.header(name, values[0])
            }
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            }
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string(),
                response.request.url.toString(),
            )
        }
    }
}
