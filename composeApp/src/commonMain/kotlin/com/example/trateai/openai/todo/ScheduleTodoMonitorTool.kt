package com.example.trateai.openai.todo

import com.example.trateai.openai.mcp.McpToolHandler
import com.example.trateai.openai.mcp.McpToolParameters
import com.example.trateai.openai.mcp.McpToolProperty
import com.example.trateai.openai.mcp.McpToolSpec

class ScheduleTodoMonitorTool : McpToolHandler {

    override val spec = McpToolSpec(
        name = "schedule_todo_monitor",
        description = "Schedule periodic monitoring of todo items and save historical snapshots",
        parameters = McpToolParameters(
            properties = mapOf(
                "interval_hours" to McpToolProperty(
                    type = "integer",
                    description = "How often to run monitoring in hours"
                )
            ),
            required = listOf("interval_hours")
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): String {
        val intervalHours = arguments["interval_hours"]
            ?.toString()
            ?.toIntOrNull()
            ?: return "Invalid argument: interval_hours must be integer"

        if (intervalHours < 1) {
            return "Invalid argument: interval_hours must be >= 1"
        }

        TodoMonitorPlatform.scheduleTodoMonitor(intervalHours)

        return "Todo monitor scheduled every $intervalHours hour(s)"
    }
}