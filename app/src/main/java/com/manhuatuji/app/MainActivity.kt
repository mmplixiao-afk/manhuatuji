package com.manhuatuji.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

val Paper = Color(0xFFFAF7F2)
val Ink = Color(0xFF1C1C1C)
val Muted = Color(0xFF6F6A63)
val Accent = Color(0xFFD01212)
val Line = Color(0xFFE6E0D6)
val CardWhite = Color.White

class MainActivity : ComponentActivity() {
    private val store by lazy { ProjectStore(this) }
    private val settings by lazy { AppSettings(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Accent, background = Paper, surface = CardWhite)) {
                Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
                    AppRoot(store, settings)
                }
            }
        }
    }
}

@Composable
private fun AppRoot(store: ProjectStore, settings: AppSettings) {
    var route by rememberSaveable { mutableStateOf("home") }
    var projectId by rememberSaveable { mutableStateOf("") }
    var previewPanel by rememberSaveable { mutableIntStateOf(-1) }
    var banner by rememberSaveable { mutableStateOf("") }
    BackHandler(route != "home") {
        route = when (route) {
            "preview" -> "project"
            "prompts" -> "settings"
            else -> "home"
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        when (route) {
            "home" -> HomeScreen(store, onOpen = { projectId = it; route = "project" }, onSettings = { route = "settings" })
            "settings" -> SettingsScreen(settings, onBack = { route = "home" }, onPrompts = { route = "prompts" }, onMessage = { banner = it })
            "prompts" -> PromptScreen(settings, onBack = { route = "settings" }, onMessage = { banner = it })
            "project" -> ProjectScreen(
                store = store,
                projectId = projectId,
                banner = banner,
                onBanner = { banner = it },
                onBack = { route = "home" },
                onPreview = { previewPanel = it; route = "preview" },
            )
            "preview" -> PreviewScreen(store, projectId, previewPanel, onBack = { route = "project" }, onMessage = { banner = it })
        }
    }
}

fun startGeneration(
    context: android.content.Context,
    projectId: String,
    action: String,
    panelId: Int = -1,
    feedback: String = "",
) {
    val intent = Intent(context, GenerationService::class.java)
        .setAction(action)
        .putExtra(GenerationService.EXTRA_PROJECT, projectId)
        .putExtra(GenerationService.EXTRA_PANEL, panelId)
        .putExtra(GenerationService.EXTRA_FEEDBACK, feedback)
    ContextCompat.startForegroundService(context, intent)
}
