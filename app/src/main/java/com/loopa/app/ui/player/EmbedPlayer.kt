package com.loopa.app.ui.player

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Awaryjny odtwarzacz: oficjalny embed YouTube'a albo TikToka w WebView.
 *
 * Używany tylko wtedy, gdy nie udało się ustalić adresu strumienia — klip da się
 * obejrzeć, ale bez grania w tle i bez własnych punktów pętli, bo tym sterujemy
 * po stronie ExoPlayera, a nie cudzego odtwarzacza.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EmbedPlayer(url: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { view ->
            if (view.url != url) view.loadUrl(url)
        },
        onRelease = { view ->
            // Bez tego dźwięk z embedu leciałby dalej po opuszczeniu ekranu.
            view.loadUrl("about:blank")
            view.destroy()
        },
    )
}
