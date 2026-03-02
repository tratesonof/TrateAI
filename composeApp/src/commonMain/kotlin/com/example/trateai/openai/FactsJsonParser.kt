package com.example.trateai.openai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object FactsJsonParser {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    @Serializable
    private data class FactsEnvelope(val facts: Map<String, String> = emptyMap())

    fun tryParseFacts(raw: String): Map<String, String>? {
        // Стараемся найти первый JSON-объект в ответе
        val trimmed = raw.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) return null

        val candidate = trimmed.substring(start, end + 1)
        return runCatching { json.decodeFromString(FactsEnvelope.serializer(), candidate).facts }
            .getOrNull()
            ?.filterKeys { it.isNotBlank() }
            ?.mapValues { it.value.trim() }
    }
}