package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.with
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.example.history.HistoryScreen
import com.example.home.HomeScreen
import com.example.patterns.PatternsScreen
import com.example.settings.SettingsScreen
import com.example.tuning.TuningScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalAnimationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            // Force Dark Theme for a beautiful, eye-safe nocturnal black dashboard (System Design Section 1 & 13)
            MyApplicationTheme(darkTheme = true) {
                // State-based Navigation (0: Home/Dashboard, 1: Patterns, 2: History, 3: Config)
                var currentTab by remember { mutableStateOf(0) }
                var showTuningScreen by remember { mutableStateOf(false) }

                BackHandler(enabled = !showTuningScreen && currentTab != 0) {
                    currentTab = 0
                }

                if (showTuningScreen) {
                    TuningScreen(
                        onBack = { showTuningScreen = false }
                    )
                } else {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        bottomBar = {
                            NavigationBar(
                                modifier = Modifier.testTag("app_navigation_bar")
                            ) {
                                NavigationBarItem(
                                    selected = currentTab == 0,
                                    onClick = { currentTab = 0 },
                                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = "Dashboard") },
                                    label = { Text("Ride") },
                                    modifier = Modifier.testTag("nav_btn_dashboard")
                                )
                                NavigationBarItem(
                                    selected = currentTab == 1,
                                    onClick = { currentTab = 1 },
                                    icon = { Icon(Icons.Default.Palette, contentDescription = "Colors") },
                                    label = { Text("Patterns") },
                                    modifier = Modifier.testTag("nav_btn_patterns")
                                )
                                NavigationBarItem(
                                    selected = currentTab == 2,
                                    onClick = { currentTab = 2 },
                                    icon = { Icon(Icons.Default.History, contentDescription = "Logs") },
                                    label = { Text("History") },
                                    modifier = Modifier.testTag("nav_btn_logs")
                                )
                                NavigationBarItem(
                                    selected = currentTab == 3,
                                    onClick = { currentTab = 3 },
                                    icon = { Icon(Icons.Default.Settings, contentDescription = "Config") },
                                    label = { Text("Config") },
                                    modifier = Modifier.testTag("nav_btn_calibrate")
                                )
                            }
                        }
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            AnimatedContent(
                                targetState = currentTab,
                                transitionSpec = { fadeIn() with fadeOut() },
                                label = "screen_transitions"
                            ) { tab ->
                                when (tab) {
                                    0 -> HomeScreen(
                                        onTabRequest = { currentTab = it },
                                        onNavigateToTuning = { showTuningScreen = true }
                                    )
                                    1 -> PatternsScreen()
                                    2 -> HistoryScreen()
                                    3 -> SettingsScreen(
                                        onNavigateToTuning = { showTuningScreen = true }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
