package com.example.trateai.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TaskStateUpdate(
    @SerialName("phase") val phase: String? = null, // planning|execution|validation|done
    @SerialName("current_step") val currentStep: String? = null,
    @SerialName("expected_action") val expectedAction: String? = null,
    @SerialName("is_paused") val isPaused: Boolean? = null
)

internal object TaskStateRouter {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    suspend fun route(
        client: OpenAiClient,
        userMessage: String,
        assistantMessage: String?,
        current: TaskFsmState,
        workingSummary: String
    ): Pair<TaskStateUpdate?, ResponseUsage?> {

        val model = "gpt-5-mini"

        val prompt = buildString {
            append("Ты обновляешь состояние задачи как конечный автомат (FSM).\n")
            append("Фазы задачи: planning → execution → validation → done\n")
            append("FSM строго запрещает перепрыгивание этапов\n")
            append("Текущая фаза — источник истины\n\n")

            append("Правила:\n")
            append("1. Если FSM говорит, что переход запрещён, не меняй фазу.\n")
            append("2. Никогда не говори, что задача завершена, если текущая фаза не DONE.\n")
            append("3. Продолжай работу в текущей фазе, задавай следующий шаг.\n")
            append("4. current_step и expected_action — коротко, 1 строка.\n")
            append("5. Если задача на паузе, не продвигай фазу.\n")
            append("6. Верни строго JSON без текста:\n")
            append("{\n")
            append("  \"phase\": \"planning|execution|validation|done\",\n")
            append("  \"current_step\": \"...\",\n")
            append("  \"expected_action\": \"...\",\n")
            append("  \"is_paused\": true|false\n")
            append("}\n\n")

            append("Текущее состояние:\n")
            append("phase=").append(current.phase.name.lowercase()).append("\n")
            append("current_step=").append(current.currentStep).append("\n")
            append("expected_action=").append(current.expectedAction).append("\n")
            append("is_paused=").append(current.isPaused).append("\n\n")

            if (workingSummary.isNotBlank()) {
                append("Working summary:\n")
                append(workingSummary).append("\n\n")
            }

            append("Новое сообщение пользователя:\n")
            append(userMessage).append("\n")

            if (!assistantMessage.isNullOrBlank()) {
                append("\nПоследний ответ ассистента:\n")
                append(assistantMessage)
            }
        }

        val res = client.chat(
            messages = listOf(
                ChatMessage("system", "Ты — Task State Router. Отвечай строго JSON."),
                ChatMessage("user", prompt)
            ),
            temperature = null,
            model = model
        )

        val parsed = tryParse(res.text)
        return parsed to res.usage
    }

    private fun tryParse(raw: String): TaskStateUpdate? {
        val t = raw.trim()
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val candidate = t.substring(start, end + 1)
        return runCatching { json.decodeFromString(TaskStateUpdate.serializer(), candidate) }.getOrNull()
    }
}

internal fun TaskPhase.asWire(): String = when (this) {
    TaskPhase.PLANNING -> "planning"
    TaskPhase.EXECUTION -> "execution"
    TaskPhase.VALIDATION -> "validation"
    TaskPhase.DONE -> "done"
}

internal fun parseTaskPhase(raw: String?): TaskPhase? = when (raw?.trim()?.lowercase()) {
    "planning" -> TaskPhase.PLANNING
    "execution" -> TaskPhase.EXECUTION
    "validation" -> TaskPhase.VALIDATION
    "done" -> TaskPhase.DONE
    else -> null
}