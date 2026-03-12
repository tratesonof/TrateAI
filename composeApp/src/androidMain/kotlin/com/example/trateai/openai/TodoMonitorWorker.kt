package com.example.trateai.openai

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.trateai.openai.platformHttpClient
import com.example.trateai.openai.todo.TodoMonitorSnapshot
import com.example.trateai.openai.todo.TodoMonitorStore
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock

class TodoMonitorWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val httpClient = platformHttpClient()
    private val store = TodoMonitorStore()

    override suspend fun doWork(): Result {
        return runCatching {
            val ids = listOf(1, 2, 3)

            val todos = ids.map { id ->
                httpClient.get("https://jsonplaceholder.typicode.com/todos/$id").body<TodoDto>()
            }

            val completed = todos.filter { it.completed }.map { it.id }

            store.appendSnapshot(
                TodoMonitorSnapshot(
                    timestampMillis = Clock.System.now().toEpochMilliseconds(),
                    completedCount = completed.size,
                    totalCount = todos.size,
                    completedIds = completed
                )
            )

            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }
}

@Serializable
private data class TodoDto(
    val id: Int,
    val title: String,
    val completed: Boolean,
    @SerialName("userId") val userId: Int
)