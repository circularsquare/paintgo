package com.anita.paintgo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    var screen by remember { mutableStateOf(Screen.Map) }

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
                        IconButton(onClick = { screen = Screen.Stats }) {
                            Icon(Icons.Filled.Info, contentDescription = "View stats")
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
        when (screen) {
            Screen.Map -> MapScreen(modifier = Modifier.fillMaxSize().padding(innerPadding))
            Screen.Stats -> StatsScreen(modifier = Modifier.fillMaxSize().padding(innerPadding))
        }
    }
}
