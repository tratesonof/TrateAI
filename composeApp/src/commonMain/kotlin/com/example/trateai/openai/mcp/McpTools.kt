package com.example.trateai.openai.mcp

import com.example.trateai.openai.ToolDefinition
import com.example.trateai.openai.ToolParameters
import com.example.trateai.openai.ToolProperty

object McpTools {
    fun todoTools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "get_todo_by_id",
            description = "Get TODO item by id from JSONPlaceholder API",
            parameters = ToolParameters(
                properties = mapOf(
                    "id" to ToolProperty(
                        type = "integer",
                        description = "Todo identifier"
                    )
                ),
                required = listOf("id")
            )
        )
    )
}