package com.example.trateai.openai

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class OpenAiClient(
    private val apiKeyProvider: () -> String,
    private val httpClient: HttpClient
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    suspend fun chat(
        userText: String,
        temperature: Double?,
        model: String,
        systemText: String? = null,
    ): ChatResult {
        val input = buildList {
            if (!systemText.isNullOrBlank()) add(InputMessage("system", systemText))
            add(InputMessage("user", userText))
        }

        val req = ResponsesRequest(
            model = model,
            input = input,
            temperature = temperature
        )

        val response: HttpResponse = httpClient.post("https://api.openai.com/v1/responses") {
            header(HttpHeaders.Authorization, "Bearer ${apiKeyProvider()}")
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(req)
        }

        val raw = response.bodyAsText()

        if (!response.status.isSuccess()) {
            throw IllegalStateException("OpenAI ${response.status.value}: $raw")
        }

        val parsed = json.decodeFromString(ResponsesResponse.serializer(), raw)

        return ChatResult(
            text = parsed.extractText(),
            usage = parsed.usage
        )
    }
}

@Serializable
private data class ResponsesRequest(
    val model: String,
    val input: List<InputMessage>,
    val temperature: Double? = null,
)

@Serializable
private data class InputMessage(
    val role: String,
    val content: String
)

@Serializable
private data class ResponsesResponse(
    @SerialName("output_text") val outputText: String? = null,
    val output: List<OutputItem> = emptyList(),
    val usage: ResponseUsage? = null,
) {
    fun extractText(): String {
        outputText?.let { if (it.isNotBlank()) return it }

        return output
            .asSequence()
            .flatMap { it.content.asSequence() }
            .mapNotNull { it.text }
            .firstOrNull()
            ?: ""
    }
}

@Serializable
private data class OutputItem(
    val content: List<OutputContent> = emptyList()
)

@Serializable
private data class OutputContent(
    val text: String? = null,
    @SerialName("type") val type: String? = null
)

@Serializable
data class ResponseUsage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

data class ChatResult(
    val text: String,
    val usage: ResponseUsage?,
)