package com.example.trateai.openai

import com.example.trateai.openai.mcp.WeatherMcpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OpenAiClient(
    private val apiKeyProvider: () -> String,
    private val httpClient: HttpClient
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private var weatherClient: WeatherMcpClient? = null

    fun setWeatherClient(client: WeatherMcpClient) {
        weatherClient = client
    }

    suspend fun getWeatherByCity(city: String): Result<String> {
        return try {
            // Geocoding
            val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=$city&count=1&language=en&format=json"
            val geoResponse = httpClient.get(geoUrl)
            val geoText = geoResponse.bodyAsText()
            
            val geoJson = json.parseToJsonElement(geoText)
            val results = geoJson.jsonObject["results"]?.jsonArray
            
            if (results == null || results.isEmpty()) {
                return Result.failure(Exception("City not found: $city"))
            }
            
            val firstResult = results[0].jsonObject
            val lat = firstResult["latitude"]?.jsonPrimitive?.content?.toDoubleOrNull()
            val lon = firstResult["longitude"]?.jsonPrimitive?.content?.toDoubleOrNull()
            val name = firstResult["name"]?.jsonPrimitive?.content
            val country = firstResult["country"]?.jsonPrimitive?.content
            
            if (lat == null || lon == null) {
                return Result.failure(Exception("Could not get coordinates"))
            }
            
            // Get weather
            val weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,cloud_cover,wind_speed_10m,wind_direction_10m"
            val weatherResponse = httpClient.get(weatherUrl)
            val weatherText = weatherResponse.bodyAsText()
            
            val weatherJson = json.parseToJsonElement(weatherText)
            val currentObj = weatherJson.jsonObject["current"]?.jsonObject
            
            if (currentObj == null) {
                return Result.failure(Exception("No weather data"))
            }
            
            val temp = currentObj["temperature_2m"]?.jsonPrimitive?.content ?: "N/A"
            val humidity = currentObj["relative_humidity_2m"]?.jsonPrimitive?.content ?: "N/A"
            val feelsLike = currentObj["apparent_temperature"]?.jsonPrimitive?.content ?: "N/A"
            val wind = currentObj["wind_speed_10m"]?.jsonPrimitive?.content ?: "N/A"
            val windDir = currentObj["wind_direction_10m"]?.jsonPrimitive?.content ?: "N/A"
            val clouds = currentObj["cloud_cover"]?.jsonPrimitive?.content ?: "N/A"
            val code = currentObj["weather_code"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            
            val weatherDesc = getWeatherDescription(code)
            
            val result = """
Погода в $name, ${country ?: ""}:
$weatherDesc
Температура: $temp°C (ощущается как $feelsLike°C)
Влажность: $humidity%
Ветер: $wind км/ч, направление $windDir°
Облачность: $clouds%
            """.trimIndent()
            
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getWeatherDescription(code: Int): String {
        return when (code) {
            0 -> "Ясно"
            1 -> "Преимущественно ясно"
            2 -> "Переменная облачность"
            3 -> "Пасмурно"
            45, 48 -> "Туман"
            51, 53, 55 -> "Морось"
            61, 63, 65 -> "Дождь"
            71, 73, 75 -> "Снег"
            80, 81, 82 -> "Ливень"
            95, 96, 99 -> "Гроза"
            else -> "Код $code"
        }
    }

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
        val toolsArray = StringBuilder("[")
        tools?.forEachIndexed { index, tool ->
            if (index > 0) toolsArray.append(",")
            toolsArray.append("{\"type\":\"function\",\"function\":{")
            toolsArray.append("\"name\":\"${tool.name}\",")
            toolsArray.append("\"description\":\"${tool.description.replace("\"", "\\\"").replace("\n", " ")}\",")
            toolsArray.append("\"parameters\":${serializeToolParameters(tool.parameters)}")
            toolsArray.append("}}")
        }
        toolsArray.append("]")

        val messagesJson = StringBuilder("[")
        messages.forEachIndexed { index, msg ->
            if (index > 0) messagesJson.append(",")
            messagesJson.append("{\"role\":\"${msg.role}\",")
            messagesJson.append("\"content\":\"${msg.content.replace("\"", "\\\"").replace("\n", "\\n")}\"}")
        }
        messagesJson.append("]")

        val sb = StringBuilder()
        sb.append("{\"model\":\"$model\",\"input\":$messagesJson")
        if (temperature != null) {
            sb.append(",\"temperature\":$temperature")
        }
        if (tools != null && tools.isNotEmpty()) {
            sb.append(",\"tools\":$toolsArray")
        }
        sb.append("}")

        val response: HttpResponse = httpClient.post("https://api.openai.com/v1/responses") {
            header(HttpHeaders.Authorization, "Bearer ${apiKeyProvider()}")
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(sb.toString())
        }

        val raw = response.bodyAsText()

        if (!response.status.isSuccess()) {
            throw IllegalStateException("OpenAI ${response.status.value}: $raw")
        }

        val parsed = json.decodeFromString(ResponsesResponse.serializer(), raw)

        // Check for tool calls
        val toolCalls = parsed.extractToolCalls()
        if (toolCalls.isNotEmpty() && weatherClient != null) {
            val toolResults = mutableListOf<ChatMessage>()
            
            for (toolCall in toolCalls) {
                val result = runBlocking { executeToolCall(toolCall) }
                toolResults.add(ChatMessage(
                    role = "tool",
                    content = result
                ))
            }

            // Continue conversation with tool results
            val newMessages = messages.toMutableList()
            newMessages.add(ChatMessage(role = "assistant", content = parsed.extractText()))
            newMessages.addAll(toolResults)

            return chatWithTools(newMessages, temperature, model, null)
        }

        return ChatResult(
            text = parsed.extractText(),
            usage = parsed.usage
        )
    }

    private suspend fun executeToolCall(toolCall: ToolCall): String {
        val name = toolCall.name
        val args = toolCall.arguments

        return when {
            name == "getweatherdata" -> {
                val latAny = args["lat"]
                val lonAny = args["lon"]
                val lat = (latAny as? Number)?.toDouble()
                val lon = (lonAny as? Number)?.toDouble()
                val appid = args["appid"] as? String ?: ""
                try {
                    if (lat != null && lon != null) {
                        val result = weatherClient?.getWeatherByCoords(lat, lon, appid)
                        result?.getOrNull() ?: "Error getting weather"
                    } else {
                        "Please provide lat and lon coordinates"
                    }
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
            }
            else -> "Unknown tool: $name"
        }
    }

    private fun serializeToolParameters(params: ToolParameters): String {
        val propsJson = params.properties.entries.joinToString(",", "{", "}") { (key, prop) ->
            "\"$key\":{\"type\":\"${prop.type}\"${if (prop.description != null) ",\"description\":\"${prop.description.replace("\"", "\\\"")}\"" else ""}}"
        }
        val requiredJson = params.required.joinToString(",", "[", "]") { "\"$it\"" }
        return """{"type":"object","properties":$propsJson,"required":$requiredJson}"""
    }

    fun close() {
        weatherClient?.close()
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
    val name: String,
    val arguments: Map<String, Any>
)

@Serializable
private data class ResponsesRequest(
    val model: String,
    val input: List<ChatMessage>,
    val temperature: Double? = null,
    val tools: List<ToolDefinition>? = null
)

@Serializable
private data class ResponsesResponse(
    @SerialName("output_text") val outputText: String? = null,
    val output: List<OutputItem> = emptyList(),
    val usage: ResponseUsage? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallItem>? = null
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

    fun extractToolCalls(): List<ToolCall> {
        return toolCalls?.mapNotNull { call ->
            val func = call.function ?: return@mapNotNull null
            try {
                val argsMap = parseJsonArguments(func.arguments)
                ToolCall(
                    id = call.id ?: "",
                    name = func.name,
                    arguments = argsMap
                )
            } catch (e: Exception) {
                null
            }
        } ?: emptyList()
    }

    private fun parseJsonArguments(arguments: String): Map<String, Any> {
        return try {
            val map = mutableMapOf<String, Any>()
            val content = arguments.trim()
            if (content.startsWith("{") && content.endsWith("}")) {
                val inner = content.substring(1, content.length - 1)
                val pairs = inner.split(",").map { it.trim() }
                for (pair in pairs) {
                    val colonIdx = pair.indexOf(':')
                    if (colonIdx > 0) {
                        val key = pair.substring(0, colonIdx).trim().trim('"')
                        val value = pair.substring(colonIdx + 1).trim().trim('"')
                        map[key] = value
                    }
                }
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
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