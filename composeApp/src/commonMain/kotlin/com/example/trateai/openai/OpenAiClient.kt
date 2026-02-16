package com.example.trateai.openai

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class OpenAiClient(
    private val apiKeyProvider: () -> String,
    private val httpClient: HttpClient
) {
    suspend fun chat(
        userText: String,
        systemText: String? = null,
        model: String = "gpt-5.2"
    ): String {
        val input = buildList {
            if (!systemText.isNullOrBlank()) add(InputMessage("system", systemText))
            add(InputMessage("user", userText))
        }

        val resp: ResponsesResponse = httpClient.post("https://api.openai.com/v1/responses") {
            header(HttpHeaders.Authorization, "Bearer ${apiKeyProvider()}")
            contentType(ContentType.Application.Json)
            setBody(ResponsesRequest(model = model, input = input))
        }.body()

        return resp.extractText()
    }
}

@Serializable
private data class ResponsesRequest(
    val model: String,
    val input: List<InputMessage>
)

@Serializable
private data class InputMessage(
    val role: String,
    val content: String
)

@Serializable
private data class ResponsesResponse(
    @SerialName("output_text") val outputText: String? = null,
    val output: List<OutputItem> = emptyList()
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
