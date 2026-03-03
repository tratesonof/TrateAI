package com.example.trateai.openai

import kotlinx.serialization.json.Json

internal object MemoryRouter {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    suspend fun route(
        client: OpenAiClient,
        userMessage: String,
        assistantMessage: String?, // можно null: роутим сразу после user
        workingFacts: Map<String, String>,
        longTermProfile: Map<String, String>,
        longTermNotes: List<String>
    ): Pair<MemoryRouterResult?, ResponseUsage?> {
        val model = "gpt-5-mini"

        val prompt = buildString {
            append("Ты маршрутизируешь память диалога по 3 типам: short-term, working, long-term.\n")
            append("short-term НЕ трогай (её хранит клиент отдельно).\n")
            append("Твоя задача: вернуть JSON с изменениями для working и long-term.\n\n")

            append("Формат ответа: строго JSON без текста вокруг:\n")
            append("{\n")
            append("  \"working_facts_delta\": {\"upsert\": {\"key\": \"value\"}, \"remove\": [\"key\"]},\n")
            append("  \"long_term_delta\": {\"upsert\": {\"key\": \"value\"}, \"remove\": [\"key\"]},\n")
            append("  \"working_summary_delta\": \"...\" | null,\n")
            append("  \"long_term_notes_delta\": [\"...\"]\n")
            append("}\n\n")

            append("Правила:\n")
            append("- Рабочая память (working): цель/задача, ограничения, требования, текущий план, прогресс, договорённости в рамках ТЕКУЩЕЙ задачи.\n")
            append("- Долговременная (long-term): стабильные предпочтения, профиль, принципы, повторяющиеся решения/правила, знания о пользователе.\n")
            append("- Не выдумывай. Если нет полезных обновлений — верни пустые deltas.\n")
            append("- Ключи: snake_case, короткие.\n")
            append("- Значения: 1-2 предложения, без воды.\n")
            append("- Не дублируй в long-term то, что относится только к текущей задаче.\n\n")

            append("Текущие working facts:\n")
            append(workingFacts.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}: ${it.value}" }.ifBlank { "(empty)" })
            append("\n\nТекущий long-term профиль:\n")
            append(longTermProfile.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}: ${it.value}" }.ifBlank { "(empty)" })
            append("\n\nLong-term notes:\n")
            append(longTermNotes.joinToString("\n").ifBlank { "(empty)" })

            append("\n\nНовое сообщение пользователя:\n")
            append(userMessage)

            if (!assistantMessage.isNullOrBlank()) {
                append("\n\nПоследний ответ ассистента (для контекста):\n")
                append(assistantMessage)
            }
        }

        val res = client.chat(
            messages = listOf(
                ChatMessage("system", "Ты — Memory Router. Отвечай строго JSON."),
                ChatMessage("user", prompt)
            ),
            temperature = null,
            model = model
        )

        val parsed = tryParse(res.text)
        return parsed to res.usage
    }

    private fun tryParse(raw: String): MemoryRouterResult? {
        val t = raw.trim()
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val candidate = t.substring(start, end + 1)
        return runCatching { json.decodeFromString(MemoryRouterResult.serializer(), candidate) }.getOrNull()
    }
}