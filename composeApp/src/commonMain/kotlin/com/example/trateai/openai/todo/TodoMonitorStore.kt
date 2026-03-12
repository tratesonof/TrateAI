package com.example.trateai.openai.todo

import com.example.trateai.openai.KvStore
import com.example.trateai.openai.createKvStore
import kotlinx.serialization.json.Json

private const val KEY_TODO_MONITOR_STATE = "todo_monitor_state_v1"

class TodoMonitorStore(
    private val kv: KvStore = createKvStore()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun load(): TodoMonitorState {
        val raw = kv.getString(KEY_TODO_MONITOR_STATE) ?: return TodoMonitorState()
        return runCatching {
            json.decodeFromString(TodoMonitorState.serializer(), raw)
        }.getOrElse { TodoMonitorState() }
    }

    fun save(state: TodoMonitorState) {
        kv.putString(KEY_TODO_MONITOR_STATE, json.encodeToString(TodoMonitorState.serializer(), state))
    }

    fun appendSnapshot(snapshot: TodoMonitorSnapshot) {
        val old = load()
        val newState = old.copy(
            lastRunAtMillis = snapshot.timestampMillis,
            runs = (old.runs + snapshot).takeLast(50)
        )
        save(newState)
    }

    fun saveSchedule(intervalHours: Int) {
        val old = load()
        save(old.copy(intervalHours = intervalHours))
    }
}