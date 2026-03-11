package com.example.trateai.openai

import com.example.trateai.openai.mcp.McpToolRegistry
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

class OpenAiClient(
    private val apiKeyProvider: () -> String,
    private val httpClient: HttpClient
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    private var toolRegistry: McpToolRegistry? = null

    fun setToolRegistry(registry: McpToolRegistry) {
        toolRegistry = registry
    }

    fun availableTools(): List<ToolDefinition> = toolRegistry?.definitions().orEmpty()

    suspend fun chat(
        messages: List<ChatMessage>,
        temperature: Double?,
        model: String,
    ): ChatResult {
        return chatWithTools(messages, temperature, model, null)
    }

    suspend fun chatWithTools(
        messages: List<ChatMessage>,
        temperature: Double?,
        model: String,
        tools: List<ToolDefinition>?
    ): ChatResult {
        val body = buildInitialRequestBody(
            messages = messages,
            temperature = temperature,
            model = model,
            tools = tools
        )

        val raw = performResponsesRequest(body)
        val parsed = json.decodeFromString(ResponsesResponse.serializer(), raw)
        val toolCalls = parsed.extractToolCalls()

        if (toolCalls.isNotEmpty() && toolRegistry != null) {
            return continueAfterToolCall(
                messages = messages,
                parsed = parsed,
                toolCalls = toolCalls,
                temperature = temperature,
                model = model,
                tools = tools
            )
        }

        return ChatResult(
            text = parsed.extractText(),
            usage = parsed.usage
        )
    }

    fun close() = Unit

    private suspend fun continueAfterToolCall(
        messages: List<ChatMessage>,
        parsed: ResponsesResponse,
        toolCalls: List<ToolCall>,
        temperature: Double?,
        model: String,
        tools: List<ToolDefinition>?
    ): ChatResult {
        val inputItems = mutableListOf<String>()

        messages.forEach { msg ->
            inputItems += messageToInputItem(msg)
        }

        parsed.output
            .filter { it.type == "reasoning" }
            .forEach { item ->
                val encrypted = item.encryptedContent
                if (!encrypted.isNullOrBlank()) {
                    inputItems += buildString {
                        append("{")
                        append("\"type\":\"reasoning\",")
                        append("\"encrypted_content\":\"${escapeJson(encrypted)}\"")
                        append("}")
                    }
                }
            }

        for (toolCall in toolCalls) {
            val toolResult = toolRegistry!!.execute(toolCall.name, toolCall.arguments)
            inputItems += functionCallItem(toolCall)
            inputItems += functionCallOutputItem(toolCall.callId, toolResult)
        }

        val body = buildString {
            append("{")
            append("\"model\":\"${escapeJson(model)}\",")
            append("\"input\":${serializeInputItems(inputItems)}")
            if (temperature != null) {
                append(",\"temperature\":$temperature")
            }
            if (!tools.isNullOrEmpty()) {
                append(",\"tools\":${serializeTools(tools)}")
            }
            append("}")
        }

        val raw = performResponsesRequest(body)
        val next = json.decodeFromString(ResponsesResponse.serializer(), raw)
        val nextToolCalls = next.extractToolCalls()

        if (nextToolCalls.isNotEmpty() && toolRegistry != null) {
            return continueAfterToolCall(
                messages = messages,
                parsed = next,
                toolCalls = nextToolCalls,
                temperature = temperature,
                model = model,
                tools = tools
            )
        }

        return ChatResult(
            text = next.extractText(),
            usage = mergeUsage(parsed.usage, next.usage)
        )
    }

    private fun mergeUsage(first: ResponseUsage?, second: ResponseUsage?): ResponseUsage? {
        if (first == null && second == null) return null
        return ResponseUsage(
            inputTokens = (first?.inputTokens ?: 0) + (second?.inputTokens ?: 0),
            outputTokens = (first?.outputTokens ?: 0) + (second?.outputTokens ?: 0),
            totalTokens = (first?.totalTokens ?: 0) + (second?.totalTokens ?: 0),
        )
    }

    private suspend fun performResponsesRequest(body: String): String {
        val response: HttpResponse = httpClient.post("https://api.openai.com/v1/responses") {
            header(HttpHeaders.Authorization, "Bearer ${apiKeyProvider()}")
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        val raw = response.bodyAsText()

        if (!response.status.isSuccess()) {
            throw IllegalStateException("OpenAI ${response.status.value}: $raw")
        }

        return raw
    }

    private fun buildInitialRequestBody(
        messages: List<ChatMessage>,
        temperature: Double?,
        model: String,
        tools: List<ToolDefinition>?
    ): String {
        val messagesJson = StringBuilder("[")
        messages.forEachIndexed { index, msg ->
            if (index > 0) messagesJson.append(",")
            messagesJson.append("{")
            messagesJson.append("\"role\":\"${escapeJson(msg.role)}\",")
            messagesJson.append("\"content\":\"${escapeJson(msg.content)}\"")
            messagesJson.append("}")
        }
        messagesJson.append("]")

        return buildString {
            append("{")
            append("\"model\":\"${escapeJson(model)}\",")
            append("\"input\":$messagesJson")
            if (temperature != null) {
                append(",\"temperature\":$temperature")
            }
            if (!tools.isNullOrEmpty()) {
                append(",\"tools\":${serializeTools(tools)}")
            }
            append("}")
        }
    }

    private fun serializeTools(tools: List<ToolDefinition>): String {
        val toolsArray = StringBuilder("[")
        tools.forEachIndexed { index, tool ->
            if (index > 0) toolsArray.append(",")
            toolsArray.append("{")
            toolsArray.append("\"type\":\"function\",")
            toolsArray.append("\"name\":\"${escapeJson(tool.name)}\",")
            toolsArray.append("\"description\":\"${escapeJson(tool.description)}\",")
            toolsArray.append("\"parameters\":${serializeToolParameters(tool.parameters)}")
            toolsArray.append("}")
        }
        toolsArray.append("]")
        return toolsArray.toString()
    }

    private fun serializeInputItems(items: List<String>): String {
        return items.joinToString(prefix = "[", postfix = "]", separator = ",")
    }

    private fun messageToInputItem(message: ChatMessage): String {
        val contentType = when (message.role) {
            "assistant" -> "output_text"
            "user", "system", "developer" -> "input_text"
            else -> "input_text"
        }

        return buildString {
            append("{")
            append("\"type\":\"message\",")
            append("\"role\":\"${escapeJson(message.role)}\",")
            append("\"content\":[{")
            append("\"type\":\"$contentType\",")
            append("\"text\":\"${escapeJson(message.content)}\"")
            append("}]")
            append("}")
        }
    }

    private fun functionCallItem(toolCall: ToolCall): String {
        return buildString {
            append("{")
            append("\"type\":\"function_call\",")
            append("\"call_id\":\"${escapeJson(toolCall.callId)}\",")
            append("\"name\":\"${escapeJson(toolCall.name)}\",")
            append("\"arguments\":\"${escapeJson(toolCall.rawArguments)}\"")
            append("}")
        }
    }

    private fun functionCallOutputItem(callId: String, output: String): String {
        return buildString {
            append("{")
            append("\"type\":\"function_call_output\",")
            append("\"call_id\":\"${escapeJson(callId)}\",")
            append("\"output\":\"${escapeJson(output)}\"")
            append("}")
        }
    }

    private fun escapeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun serializeToolParameters(params: ToolParameters): String {
        val propsJson = params.properties.entries.joinToString(",", "{", "}") { (key, prop) ->
            buildString {
                append("\"")
                append(escapeJson(key))
                append("\":{")
                append("\"type\":\"${escapeJson(prop.type)}\"")
                prop.description?.let {
                    append(",\"description\":\"${escapeJson(it)}\"")
                }
                append("}")
            }
        }
        val requiredJson = params.required.joinToString(",", "[", "]") { "\"${escapeJson(it)}\"" }
        return """{"type":"object","properties":$propsJson,"required":$requiredJson}"""
    }

    @Serializable
    private data class ResponsesResponse(
        @SerialName("output_text") val outputText: String? = null,
        val output: List<OutputItem> = emptyList(),
        val usage: ResponseUsage? = null,
        @SerialName("tool_calls") val toolCalls: List<ToolCallItem>? = null
    ) {
        fun extractText(): String {
            outputText?.let { if (it.isNotBlank()) return it }

            val fromContent = output
                .asSequence()
                .flatMap { it.content.asSequence() }
                .mapNotNull { it.text }
                .firstOrNull { it.isNotBlank() }

            if (!fromContent.isNullOrBlank()) return fromContent

            return ""
        }

        fun extractToolCalls(): List<ToolCall> {
            val fromOutputItems = output.mapNotNull { item ->
                if (item.type != "function_call" || item.name.isNullOrBlank()) return@mapNotNull null

                ToolCall(
                    id = item.id.orEmpty(),
                    callId = item.callId.orEmpty(),
                    name = item.name,
                    arguments = runCatching {
                        parseJsonArguments(item.arguments.orEmpty())
                    }.getOrDefault(emptyMap()),
                    rawArguments = item.arguments.orEmpty()
                )
            }

            val fromLegacyField = toolCalls.orEmpty().mapNotNull { call ->
                val function = call.function ?: return@mapNotNull null
                ToolCall(
                    id = call.id ?: "",
                    callId = call.id ?: "",
                    name = function.name,
                    arguments = runCatching {
                        parseJsonArguments(function.arguments)
                    }.getOrDefault(emptyMap()),
                    rawArguments = function.arguments
                )
            }

            return (fromOutputItems + fromLegacyField)
                .distinctBy { "${it.callId}:${it.name}:${it.rawArguments}" }
        }
    }

    @Serializable
    private data class OutputItem(
        val id: String? = null,
        val type: String? = null,
        val name: String? = null,
        val arguments: String? = null,
        @SerialName("call_id") val callId: String? = null,
        @SerialName("encrypted_content") val encryptedContent: String? = null,
        val content: List<OutputContent> = emptyList()
    )

    @Serializable
    private data class OutputContent(
        val text: String? = null,
        @SerialName("type") val type: String? = null
    )

    @Serializable
    private data class ToolCallItem(
        val id: String? = null,
        val type: String? = null,
        @SerialName("function") val function: FunctionCall? = null
    )

    @Serializable
    private data class FunctionCall(
        val name: String,
        val arguments: String
    )

    private companion object {
        private val toolArgsJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            isLenient = true
        }

        private fun parseJsonArguments(arguments: String): Map<String, Any> {
            val jsonElement = toolArgsJson.parseToJsonElement(arguments)
            return jsonElement.jsonObject.mapValues { (_, value) -> value.toKotlinValue() }
        }

        private fun JsonElement.toKotlinValue(): Any {
            return when (this) {
                is JsonPrimitive -> {
                    when {
                        isString -> content
                        booleanOrNull != null -> boolean
                        intOrNull != null -> int
                        doubleOrNull != null -> double
                        else -> content
                    }
                }
                else -> toString()
            }
        }
    }
}

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val name: String,
    val description: String,
    val parameters: ToolParameters
)

@Serializable
data class ToolParameters(
    val type: String = "object",
    val properties: Map<String, ToolProperty>,
    val required: List<String> = emptyList()
)

@Serializable
data class ToolProperty(
    val type: String,
    val description: String? = null
)

data class ToolCall(
    val id: String,
    val callId: String,
    val name: String,
    val arguments: Map<String, Any>,
    val rawArguments: String
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