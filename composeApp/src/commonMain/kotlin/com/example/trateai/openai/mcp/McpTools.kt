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

    fun schedulerTools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "schedule_todo_monitor",
            description = "Schedule periodic monitoring of todo items",
            parameters = ToolParameters(
                properties = mapOf(
                    "interval_hours" to ToolProperty(
                        type = "integer",
                        description = "How often to run monitoring in hours"
                    )
                ),
                required = listOf("interval_hours")
            )
        ),
        ToolDefinition(
            name = "get_todo_monitor_report",
            description = "Get aggregated report for periodic todo monitoring",
            parameters = ToolParameters(
                properties = emptyMap(),
                required = emptyList()
            )
        )
    )
}