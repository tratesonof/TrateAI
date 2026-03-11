package com.example.trateai.openai.mcp

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class JsonPlaceholderTodoTool(
    private val httpClient: HttpClient
) : McpToolHandler {

    override val spec = McpToolSpec(
        name = "get_todo_by_id",
        description = "Get TODO item by id from JSONPlaceholder test API",
        parameters = McpToolParameters(
            properties = mapOf(
                "id" to McpToolProperty(
                    type = "integer",
                    description = "Todo identifier"
                )
            ),
            required = listOf("id")
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): String {
        val id = arguments["id"]
            ?.toString()
            ?.toIntOrNull()
            ?: return "Invalid argument: id must be integer"

        val dto: TodoDto = httpClient
            .get("https://jsonplaceholder.typicode.com/todos/$id")
            .body()

        return buildString {
            appendLine("Todo #${dto.id}")
            appendLine("Title: ${dto.title}")
            appendLine("Completed: ${dto.completed}")
            appendLine("UserId: ${dto.userId}")
        }.trim()
    }
}

@Serializable
data class TodoDto(
    val id: Int,
    val title: String,
    val completed: Boolean,
    @SerialName("userId") val userId: Int
)