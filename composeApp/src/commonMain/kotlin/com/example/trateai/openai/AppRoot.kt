package com.example.trateai.openai

import androidx.compose.runtime.*

@Composable
fun AppRoot() {
    var screen by remember { mutableStateOf(AppScreen.Chat) }

    val controller = rememberChatController()

    when (screen) {
        AppScreen.Chat -> ChatScreen(
            controller = controller,
            onOpenSettings = { screen = AppScreen.Settings }
        )

        AppScreen.Settings -> SettingsScreen(
            controller = controller,
            onBack = { screen = AppScreen.Chat }
        )
    }
}

private enum class AppScreen { Chat, Settings }