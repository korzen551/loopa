package com.loopa.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/**
 * Wpis TikToka ze zdjeciami zamiast wideo. Muzyka gra w odtwarzaczu, a tu
 * leca zdjecia - jak w TikToku: same przechodza co kilka sekund, palcem mozna
 * szybciej, a licznik mowi, ktore to zdjecie.
 */
@Composable
fun PhotoCarousel(
    images: List<String>,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    /** Odstep licznika od dolu - w feedzie nad podpisem filmu. */
    indicatorBottomPadding: Dp = 140.dp,
) {
    val pager = rememberPagerState(pageCount = { images.size })

    // Kazde przesuniecie (samo albo palcem) liczy czas od nowa; pauza zatrzymuje.
    LaunchedEffect(isPlaying, pager.settledPage, images.size) {
        if (!isPlaying || images.size < 2) return@LaunchedEffect
        delay(SLIDE_MS)
        pager.animateScrollToPage((pager.settledPage + 1) % images.size)
    }

    Box(modifier) {
        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            AsyncImage(
                model = images[page],
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (images.size > 1) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = indicatorBottomPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (images.size <= MAX_DOTS) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.35f))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        repeat(images.size) { index ->
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (index == pager.currentPage) Color.White else Color.White.copy(alpha = 0.4f)
                                    )
                            )
                        }
                    }
                } else {
                    Text(
                        "${pager.currentPage + 1} / ${images.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.45f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

private const val SLIDE_MS = 3_000L
private const val MAX_DOTS = 12
