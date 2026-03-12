package com.example.trateai.openai.todo

import com.example.trateai.openai.AndroidContextProvider
import com.example.trateai.openai.TodoMonitorScheduler

actual object TodoMonitorPlatform {

    actual fun scheduleTodoMonitor(intervalHours: Int) {
        TodoMonitorScheduler(AndroidContextProvider.context)
            .schedule(intervalHours)
    }

}