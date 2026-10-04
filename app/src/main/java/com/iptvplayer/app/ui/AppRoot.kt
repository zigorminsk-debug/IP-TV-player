package com.iptvplayer.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.iptvplayer.app.R
import com.iptvplayer.app.data.repo.UpdateRepository
import com.iptvplayer.app.ui.screens.channels.ChannelsScreen
import com.iptvplayer.app.ui.screens.editor.EditorScreen
import com.iptvplayer.app.ui.screens.epg.EpgSourcesScreen
import com.iptvplayer.app.ui.screens.guide.GuideScreen
import com.iptvplayer.app.ui.screens.player.PlayerScreen
import com.iptvplayer.app.ui.screens.playlists.PlaylistsScreen
import com.iptvplayer.app.ui.screens.search.SearchScreen
import com.iptvplayer.app.ui.screens.series.SeriesScreen
import com.iptvplayer.app.ui.screens.settings.SettingsScreen

object Routes {
    const val PLAYLISTS = "playlists"
    const val CHANNELS = "channels"
    const val GUIDE = "guide"
    const val PLAYER = "player"
    const val SERIES = "series"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val EDITOR = "editor"
    const val EPG_SOURCES = "epg_sources"
}

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val appVm: AppViewModel = viewModel()

    LaunchedEffect(Unit) { appVm.autoCheck() }
    RequestNotificationPermission()
    UpdateDialogs(appVm)

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val bottomRoutes = remember { listOf(Routes.PLAYLISTS, Routes.SEARCH, Routes.SETTINGS) }

    Scaffold(
        bottomBar = {
            if (currentRoute in bottomRoutes) {
                NavigationBar {
                    bottomRoutes.forEach { r ->
                        val (labelRes, icon) = when (r) {
                            Routes.PLAYLISTS ->
                                R.string.tab_playlists to Icons.Filled.PlaylistPlay
                            Routes.SEARCH ->
                                R.string.tab_search to Icons.Filled.Search
                            else ->
                                R.string.tab_settings to Icons.Filled.Settings
                        }
                        NavigationBarItem(
                            selected = currentRoute == r,
                            onClick = {
                                navController.navigate(r) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(icon, contentDescription = null) },
                            label = {
                                Text(stringResource(labelRes))
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.PLAYLISTS,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.PLAYLISTS) { PlaylistsScreen(navController) }

            composable(
                route = "${Routes.CHANNELS}/{playlistId}",
                arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("playlistId")?.toLongOrNull()
                if (id != null) ChannelsScreen(playlistId = id, navController = navController)
            }

            composable(
                route = "${Routes.GUIDE}/{playlistId}",
                arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("playlistId")?.toLongOrNull()
                if (id != null) GuideScreen(playlistId = id, navController = navController)
            }

            composable(Routes.PLAYER) {
                PlayerScreen(onBack = { navController.popBackStack() })
            }

            composable(
                route = "${Routes.SERIES}/{playlistId}/{channelUid}",
                arguments = listOf(
                    navArgument("playlistId") { type = NavType.StringType },
                    navArgument("channelUid") { type = NavType.StringType },
                ),
            ) { entry ->
                val id = entry.arguments?.getString("playlistId")?.toLongOrNull()
                val uid = entry.arguments?.getString("channelUid")
                if (id != null && uid != null) {
                    SeriesScreen(playlistId = id, channelUid = uid, navController = navController)
                }
            }

            composable(Routes.SEARCH) { SearchScreen(navController) }
            composable(Routes.SETTINGS) { SettingsScreen(navController, appVm) }

            composable(
                route = "${Routes.EDITOR}/{playlistId}",
                arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("playlistId")?.toLongOrNull()
                if (id != null) EditorScreen(playlistId = id, navController = navController)
            }

            composable(Routes.EPG_SOURCES) { EpgSourcesScreen(navController) }
        }
    }
}

@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT >= 33) {
        val context = LocalContext.current
        var asked by rememberSaveable { mutableStateOf(false) }
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { }
        LaunchedEffect(Unit) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!asked && !granted) {
                asked = true
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

/** Self-update dialogs driven by UpdateRepository state. */
@Composable
fun UpdateDialogs(appVm: AppViewModel) {
    val state by appVm.updateState.collectAsState()
    when (val s = state) {
        is UpdateRepository.UpdateState.Available -> {
            AlertDialog(
                onDismissRequest = { appVm.resetUpdateState() },
                title = {
                    Text(
                        stringResource(
                            R.string.update_available_title,
                            s.manifest.versionName,
                            s.manifest.buildNumber,
                        ),
                    )
                },
                text = {
                    Column {
                        Text(
                            stringResource(
                                R.string.update_current_version,
                                s.currentVersionName,
                                s.currentVersionCode,
                            ),
                        )
                        if (s.manifest.releaseNotes.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(s.manifest.releaseNotes, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { appVm.download() }) {
                        Text(stringResource(R.string.update_download))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { appVm.resetUpdateState() }) {
                        Text(stringResource(R.string.later))
                    }
                },
            )
        }

        is UpdateRepository.UpdateState.Downloading -> {
            AlertDialog(
                onDismissRequest = { },
                title = { Text(stringResource(R.string.update_downloading)) },
                text = {
                    Column {
                        LinearProgressIndicator(
                            progress = { s.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${s.receivedBytes / 1024} KB" +
                                (s.totalBytes?.let { " / ${it / 1024} KB" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                confirmButton = {},
                dismissButton = {},
            )
        }

        is UpdateRepository.UpdateState.ReadyToInstall -> {
            AlertDialog(
                onDismissRequest = { },
                title = {
                    Text(
                        stringResource(
                            R.string.update_ready_title,
                            s.manifest.versionName,
                        ),
                    )
                },
                text = {
                    Text(stringResource(R.string.update_ready_text))
                },
                confirmButton = {
                    TextButton(onClick = { appVm.install() }) {
                        Text(stringResource(R.string.update_install))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { appVm.resetUpdateState() }) {
                        Text(stringResource(R.string.later))
                    }
                },
            )
        }

        is UpdateRepository.UpdateState.NeedInstallPermission -> {
            AlertDialog(
                onDismissRequest = { appVm.resetUpdateState() },
                title = {
                    Text(stringResource(R.string.update_permission_title))
                },
                text = {
                    Text(stringResource(R.string.update_permission_text))
                },
                confirmButton = {
                    TextButton(onClick = { appVm.openInstallPermissionSettings() }) {
                        Text(stringResource(R.string.open_settings))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { appVm.resetUpdateState() }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

        is UpdateRepository.UpdateState.UpToDate -> {
            AlertDialog(
                onDismissRequest = { appVm.resetUpdateState() },
                title = { Text(stringResource(R.string.update_uptodate_title)) },
                text = { Text(stringResource(R.string.update_uptodate_text)) },
                confirmButton = {
                    TextButton(onClick = { appVm.resetUpdateState() }) {
                        Text(stringResource(R.string.ok))
                    }
                },
            )
        }

        is UpdateRepository.UpdateState.Failed -> {
            AlertDialog(
                onDismissRequest = { appVm.resetUpdateState() },
                title = { Text(stringResource(R.string.update_failed_title)) },
                text = { Text(s.message) },
                confirmButton = {
                    TextButton(onClick = { appVm.resetUpdateState() }) {
                        Text(stringResource(R.string.ok))
                    }
                },
            )
        }

        else -> {}
    }
}
