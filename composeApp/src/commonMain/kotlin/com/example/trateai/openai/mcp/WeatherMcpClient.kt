package com.example.trateai.openai.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit

class WeatherMcpClient(
    private val baseUrl: String = "https://smithery.ai/mcp/@isdaniel/mcp_weather_server",
    private val httpClient: HttpClient = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(30, TimeUnit.SECONDS)
                readTimeout(60, TimeUnit.SECONDS)
            }
        }
    }
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        prettyPrint = false
    }

    private var sessionId: String? = null
    private var idCounter = 0

    suspend fun initialize(): Result<Unit> {
        return try {
            val request = buildJsonRequest(
                method = "initialize",
                params = mapOf(
                    "protocolVersion" to "2024-11-05",
                    "capabilities" to emptyMap<String, String>(),
                    "clientInfo" to mapOf("name" to "android-weather-client", "version" to "1.0")
                )
            )

            val response = sendRequest(request)
            
            if (response.error != null) {
                Result.failure(Exception("Initialize error: ${response.error.message}"))
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listTools(): Result<List<McpTool>> {
        return try {
            val request = """{"jsonrpc":"2.0","id":${++idCounter},"method":"tools/list"}"""

            val response = sendRequest(request)

            val tools = response.result?.tools?.tools ?: emptyList()
            Result.success(tools)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getWeatherByCoords(lat: Double, lon: Double, appid: String = ""): Result<String> {
        return try {
            val args = mutableMapOf<String, Any>(
                "lat" to lat,
                "lon" to lon
            )
            if (appid.isNotEmpty()) {
                args["appid"] = appid
            }
            
            val request = buildJsonRequest(
                method = "tools/call",
                params = mapOf(
                    "name" to "getweatherdata",
                    "arguments" to args
                )
            )

            val response = sendRequest(request)

            if (response.error != null) {
                Result.failure(Exception(response.error.message))
            } else {
                val content = response.result?.content
                val text = content?.firstOrNull { it.type == "text" }?.text
                    ?: content?.firstOrNull()?.text
                    ?: "No data"
                Result.success(text)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getWeatherByCity(city: String): Result<String> {
        return try {
            // Geocoding first
            val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=$city&count=1&language=en&format=json"
            val geoResponse = httpClient.get(geoUrl)
            
            val geoText = geoResponse.bodyAsText()
            val geoJson = json.parseToJsonElement(geoText)
            val results = geoJson.jsonObject["results"]?.jsonArray
            
            if (results == null || results.isEmpty()) {
                return Result.failure(Exception("City not found: $city"))
            }
            
            val firstResult = results[0].jsonObject
            val latAny = firstResult["latitude"]?.jsonPrimitive?.content
            val lonAny = firstResult["longitude"]?.jsonPrimitive?.content
            val lat = latAny?.toDoubleOrNull()
            val lon = lonAny?.toDoubleOrNull()
            val name = firstResult["name"]?.jsonPrimitive?.content
            val country = firstResult["country"]?.jsonPrimitive?.content
            
            if (lat == null || lon == null) {
                return Result.failure(Exception("Could not get coordinates"))
            }
            
            // Now get weather
            return getWeatherByCoords(lat, lon).map { weather ->
                "Weather in $name, ${country ?: ""}:\n$weather"
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAirQuality(city: String): Result<String> {
        return try {
            val request = buildJsonRequest(
                method = "tools/call",
                params = mapOf(
                    "name" to "get_air_quality",
                    "arguments" to mapOf("city" to city)
                )
            )

            val response = sendRequest(request)

            if (response.error != null) {
                Result.failure(Exception(response.error.message))
            } else {
                val content = response.result?.content
                val text = content?.firstOrNull { it.type == "text" }?.text
                    ?: content?.firstOrNull()?.text
                    ?: "No data"
                Result.success(text)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildJsonRequest(method: String, params: Any? = null): String {
        val sb = StringBuilder()
        sb.append("{\"jsonrpc\":\"2.0\",\"id\":${++idCounter},\"method\":\"$method\"")
        if (params != null) {
            when (params) {
                is Map<*, *> -> {
                    sb.append(",\"params\":")
                    sb.append(mapToJson(params as Map<String, Any>))
                }
            }
        }
        sb.append("}")
        return sb.toString()
    }

    private fun mapToJson(map: Map<String, Any>): String {
        val sb = StringBuilder("{")
        val entries = map.entries.toList()
        for ((i, entry) in entries.withIndex()) {
            if (i > 0) sb.append(",")
            sb.append("\"${entry.key}\":")
            val value = entry.value
            when (value) {
                is String -> sb.append("\"${value.replace("\"", "\\\"")}\"")
                is Number -> sb.append(value.toString())
                is Boolean -> sb.append(value.toString())
                is Map<*, *> -> sb.append(mapToJson(value as Map<String, Any>))
                else -> sb.append("\"$value\"")
            }
        }
        sb.append("}")
        return sb.toString()
    }

    private suspend fun sendRequest(request: String): McpResponse {
        try {
            val response = httpClient.post(baseUrl) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            if (response.status.value == 405) {
                // Try different endpoint
                val altResponse = httpClient.post("$baseUrl/") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                if (altResponse.status.isSuccess()) {
                    val body = altResponse.bodyAsText()
                    return json.decodeFromString<McpResponse>(body)
                }
            }

            if (!response.status.isSuccess()) {
                throw IllegalStateException("MCP Error: ${response.status.value} - ${response.bodyAsText()}")
            }

            val body = response.bodyAsText()
            return try {
                json.decodeFromString<McpResponse>(body)
            } catch (e: Exception) {
                throw IllegalStateException("Failed to parse MCP response: $body", e)
            }
        } catch (e: Exception) {
            throw IllegalStateException("MCP request failed: ${e.message}", e)
        }
    }

    fun close() {
        httpClient.close()
    }
}

@kotlinx.serialization.Serializable
data class McpResponse(
    val jsonrpc: String = "2.0",
    val id: Int? = null,
    val result: McpResult? = null,
    val error: McpError? = null
)

@kotlinx.serialization.Serializable
data class McpError(
    val code: Int,
    val message: String,
    val data: String? = null
)

@kotlinx.serialization.Serializable
data class McpResult(
    val tools: ToolsResult? = null,
    val content: List<ContentItem>? = null,
    val isError: Boolean? = null
)

@kotlinx.serialization.Serializable
data class ToolsResult(
    val tools: List<McpTool>
)

@kotlinx.serialization.Serializable
data class McpTool(
    val name: String,
    val description: String? = null,
    val inputSchema: Map<String, String>? = null
)

@kotlinx.serialization.Serializable
data class ContentItem(
    val type: String,
    val text: String? = null,
    val data: String? = null
)