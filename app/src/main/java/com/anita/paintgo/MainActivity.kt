package com.anita.paintgo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.anita.paintgo.data.AppDatabase
import com.anita.paintgo.ui.theme.PaintGoTheme

private enum class Screen { Map, Stats }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Pre-warm the DB so the seed callback fires before any screen needs the self Owner.
        AppDatabase.get(applicationContext)

        setContent {
            PaintGoTheme {
                AppRoot()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val settings = remember(context) { SettingsStore.get(context) }
    val showBoundaries by settings.showBoundaries.collectAsState()
    val showDebugGrid by settings.showDebugGrid.collectAsState()
    val fogColor by settings.fogColor.collectAsState()
    val fogOpacity by settings.fogOpacity.collectAsState()
    val viewMode by settings.viewMode.collectAsState()

    var screen by remember { mutableStateOf(Screen.Map) }
    var settingsOpen by remember { mutableStateOf(false) }
    // Hoisted so it survives the Map→Stats→Map round-trip (which destroys MapScreen
    // and resets its locals). First entry centers; later returns leave the camera alone.
    var pendingInitialCenter by remember { mutableStateOf(true) }
    // Hoisted so the Settings sheet can flip it on. MapScreen still owns the rest of
    // the delete-mode flow (selection, confirm dialog) since nothing outside it cares.
    var deleteMode by remember { mutableStateOf(false) }

    if (screen == Screen.Stats) {
        BackHandler { screen = Screen.Map }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            when (screen) {
                Screen.Map -> TopAppBar(
                    title = { Text("PaintGo") },
                    actions = {
                        IconButton(onClick = { settingsOpen = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                        IconButton(onClick = { screen = Screen.Stats }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_stats_bar),
                                contentDescription = "View stats",
                            )
                        }
                    },
                )
                Screen.Stats -> TopAppBar(
                    title = { Text("Stats") },
                    navigationIcon = {
                        IconButton(onClick = { screen = Screen.Map }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        // MapScreen always stays composed so its MapView/style/fog don't reload on
        // every Stats round-trip. When Stats is up we overlay it inside an opaque
        // Surface, which paints the theme background over the map.
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            MapScreen(
                showBoundaries = showBoundaries,
                showDebugGrid = showDebugGrid,
                fogColor = fogColor,
                fogOpacity = fogOpacity,
                viewMode = viewMode,
                centerOnUserInitially = pendingInitialCenter,
                onInitialCenterConsumed = { pendingInitialCenter = false },
                deleteMode = deleteMode,
                onDeleteModeChange = { deleteMode = it },
                modifier = Modifier.fillMaxSize(),
            )
            if (screen == Screen.Stats) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    StatsScreen(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }

    if (settingsOpen) {
        SettingsSheet(
            onDismiss = { settingsOpen = false },
            onEnterDeleteMode = {
                deleteMode = true
                settingsOpen = false
            },
        )
    }
}
