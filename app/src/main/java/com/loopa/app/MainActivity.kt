package com.loopa.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.media3.common.util.UnstableApi
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.loopa.app.media.PlayerConnection
import com.loopa.app.ui.feed.FeedScreen
import com.loopa.app.ui.library.LibraryScreen
import com.loopa.app.ui.player.PlayerScreen
import com.loopa.app.ui.playlist.PlaylistScreen
import com.loopa.app.ui.settings.SettingsScreen
import com.loopa.app.ui.theme.LoopaTheme

@UnstableApi
class MainActivity : ComponentActivity() {

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Odmowa tylko chowa sterowanie w powiadomieniu - granie dziala dalej. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            LoopaTheme {
                val navController = rememberNavController()

                LaunchedEffect(Unit) { PlayerConnection.connect(applicationContext) }

                NavHost(navController = navController, startDestination = "library") {
                    composable("library") {
                        LibraryScreen(
                            onOpenPlaylist = { navController.navigate("playlist/$it") },
                            onOpenPlayer = { navController.navigate("player") },
                            onOpenSettings = { navController.navigate("settings") },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(onBack = { navController.popBackStack() })
                    }
                    composable(
                        route = "playlist/{playlistId}",
                        arguments = listOf(navArgument("playlistId") { type = NavType.LongType }),
                    ) { entry ->
                        val playlistId = entry.arguments?.getLong("playlistId") ?: 0L
                        PlaylistScreen(
                            playlistId = playlistId,
                            onBack = { navController.popBackStack() },
                            onOpenPlayer = { navController.navigate("player") },
                            onOpenFeed = { index -> navController.navigate("feed/$playlistId?start=$index") },
                        )
                    }
                    composable(
                        route = "feed/{playlistId}?start={start}",
                        arguments = listOf(
                            navArgument("playlistId") { type = NavType.LongType },
                            navArgument("start") { type = NavType.IntType; defaultValue = 0 },
                        ),
                    ) { entry ->
                        FeedScreen(
                            playlistId = entry.arguments?.getLong("playlistId") ?: 0L,
                            startIndex = entry.arguments?.getInt("start") ?: 0,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("player") {
                        PlayerScreen(onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }
}
