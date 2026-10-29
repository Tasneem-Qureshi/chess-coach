package com.tasneem.chesscoach

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tasneem.chesscoach.capture.CoachService
import com.tasneem.chesscoach.ui.CoachScreen
import com.tasneem.chesscoach.ui.GamesScreen
import com.tasneem.chesscoach.ui.ProgressScreen
import com.tasneem.chesscoach.ui.ReportScreen
import com.tasneem.chesscoach.ui.SettingsScreen
import com.tasneem.chesscoach.ui.TrainScreen
import com.tasneem.chesscoach.ui.theme.Amoled
import com.tasneem.chesscoach.ui.theme.ChessCoachTheme

sealed interface Route {
    data object Coach : Route
    data object Games : Route
    data object Train : Route
    data object Progress : Route
    data object Settings : Route
    data class Report(val gameId: String) : Route
}

class MainActivity : ComponentActivity() {

    private val openGame = mutableStateOf<String?>(null)

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) {
            CoachService.start(this, r.resultCode, data)
            // go back to the chess app: the overlay is now on top of everything
            moveTaskToBack(true)
        }
    }
    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startCoach() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openGame.value = intent.getStringExtra(EXTRA_GAME_ID)
        setContent {
            ChessCoachTheme {
                AppRoot(openGame.value, onOpened = { openGame.value = null }, onStartCoach = ::startCoach)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_GAME_ID)?.let { openGame.value = it }
    }

    /** Overlay permission → notification permission → screen-capture consent → service. */
    fun startCoach() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !notifAsked
        ) {
            notifAsked = true
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        projectionLauncher.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }

    private var notifAsked = false

    companion object { const val EXTRA_GAME_ID = "gameId" }
}

@Composable
private fun AppRoot(openGame: String?, onOpened: () -> Unit, onStartCoach: () -> Unit) {
    var tab by remember { mutableStateOf<Route>(Route.Coach) }
    val stack = remember { mutableStateListOf<Route>() }
    LaunchedEffect(openGame) { if (openGame != null) { stack.add(Route.Report(openGame)); onOpened() } }
    val top = stack.lastOrNull()
    BackHandler(enabled = top != null) { stack.removeAt(stack.lastIndex) }
    val open: (Route) -> Unit = { stack.add(it) }

    Scaffold(
        containerColor = Amoled.Black,
        bottomBar = {
            if (top == null) NavigationBar(containerColor = Amoled.Black, tonalElevation = 0.dp) {
                listOf(
                    Triple(Route.Coach, "Coach", Icons.Default.Visibility),
                    Triple(Route.Games, "Games", Icons.AutoMirrored.Filled.List),
                    Triple(Route.Train, "Train", Icons.Default.Extension),
                    Triple(Route.Progress, "Progress", Icons.Default.Insights),
                ).forEach { (r, label, icon) ->
                    NavigationBarItem(
                        selected = tab == r, onClick = { tab = r }, icon = { Icon(icon, label) }, label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Amoled.Black, indicatorColor = Amoled.Accent,
                            selectedTextColor = Amoled.Accent, unselectedIconColor = Amoled.Muted, unselectedTextColor = Amoled.Muted,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (val r = top ?: tab) {
                Route.Coach -> CoachScreen(onStartCoach, open, onTab = { tab = it })
                Route.Games -> GamesScreen(open)
                Route.Train -> TrainScreen()
                Route.Progress -> ProgressScreen(open)
                Route.Settings -> SettingsScreen { stack.removeAt(stack.lastIndex) }
                is Route.Report -> ReportScreen(r.gameId) { stack.removeAt(stack.lastIndex) }
            }
        }
    }
}
