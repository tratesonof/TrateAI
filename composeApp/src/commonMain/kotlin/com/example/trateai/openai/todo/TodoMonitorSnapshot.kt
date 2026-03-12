package com.example.trateai.openai.todo

import kotlinx.serialization.Serializable

@Serializable
data class TodoMonitorSnapshot(
    val timestampMillis: Long,
    val completedCount: Int,
    val totalCount: Int,
    val completedIds: List<Int>
)

@Serializable
data class TodoMonitorState(
    val intervalHours: Int = 0,
    val lastRunAtMillis: Long? = null,
    val runs: List<TodoMonitorSnapshot> = emptyList()
)