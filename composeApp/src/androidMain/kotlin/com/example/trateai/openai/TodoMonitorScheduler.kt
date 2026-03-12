package com.example.trateai.openai

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.trateai.openai.todo.TodoMonitorStore
import java.util.concurrent.TimeUnit

private const val TODO_MONITOR_WORK_NAME = "todo_monitor_periodic_work"

class TodoMonitorScheduler(
    private val context: Context
) {
    private val store = TodoMonitorStore()

    fun schedule(intervalHours: Int) {
        val request = PeriodicWorkRequestBuilder<TodoMonitorWorker>(
            intervalHours.toLong(),
            TimeUnit.HOURS
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            TODO_MONITOR_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )

        store.saveSchedule(intervalHours)
    }
}