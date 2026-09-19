package com.loopa.app.data

/**
 * Sklada ostateczne ustawienia odtwarzania jednego wpisu w playliscie.
 *
 * Punkty na osi czasu (start, start petli, koniec) zawsze pochodza z filmu albo
 * z jego nadpisania w tej playliscie. Playlista decyduje wylacznie o tym, ILE RAZY
 * film ma polecic, zanim ruszymy dalej.
 *
 * Domyslnie, przy wylaczonym przewijaniu, zachowujemy sie jak TikTok: biezacy film
 * leci w kolko, a kolejny wybierasz palcem.
 */
fun effectiveLoop(playlist: Playlist?, entry: PlaylistEntry): LoopSettings {
    val points = entry.loop

    if (playlist == null || !playlist.autoAdvance) {
        return points.copy(repeatMode = RepeatMode.LOOP)
    }

    return when {
        playlist.usePerItemCount -> points.copy(
            repeatMode = RepeatMode.COUNT,
            repeatCount = entry.item.playCount.coerceAtLeast(1),
        )

        playlist.useGlobalCount -> points.copy(
            repeatMode = RepeatMode.COUNT,
            repeatCount = playlist.globalCount.coerceAtLeast(1),
        )

        playlist.advanceOnFinish -> points.copy(repeatMode = RepeatMode.OFF)

        // Przewijanie wlaczone, ale zaden z warunkow nie wybrany - nie ma sygnalu,
        // kiedy ruszyc dalej, wiec zostajemy przy zapetleniu.
        else -> points.copy(repeatMode = RepeatMode.LOOP)
    }
}

/** Ile razy ten wpis poleci, zanim playlista przejdzie dalej. Null = bez konca. */
fun effectivePlayCount(playlist: Playlist?, entry: PlaylistEntry): Int? {
    val loop = effectiveLoop(playlist, entry)
    return when (loop.repeatMode) {
        RepeatMode.LOOP -> null
        RepeatMode.OFF -> 1
        RepeatMode.COUNT -> loop.repeatCount
    }
}
