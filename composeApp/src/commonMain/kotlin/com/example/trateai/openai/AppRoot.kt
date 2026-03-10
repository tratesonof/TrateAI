package com.example.trateai.openai

import androidx.compose.runtime.*

@Composable
fun AppRoot() {
    var screen by remember { mutableStateOf(AppScreen.Chat) }

    val controller = rememberChatController()

    when (screen) {
        AppScreen.Chat -> ChatScreen(
            controller = controller,
            onOpenSettings = { screen = AppScreen.Settings },
            onOpenWeather = { screen = AppScreen.Weather }
        )

        AppScreen.Settings -> SettingsScreen(
            controller = controller,
            onBack = { screen = AppScreen.Chat }
        )

        AppScreen.Weather -> WeatherScreen(
            onBack = { screen = AppScreen.Chat }
        )
    }
}

private enum class AppScreen { Chat, Settings, Weather }