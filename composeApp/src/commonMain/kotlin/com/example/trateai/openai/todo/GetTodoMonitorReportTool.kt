package com.example.trateai.openai.todo

import com.example.trateai.openai.mcp.McpToolHandler
import com.example.trateai.openai.mcp.McpToolParameters
import com.example.trateai.openai.mcp.McpToolSpec
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.round
import kotlin.time.Instant

class GetTodoMonitorReportTool : McpToolHandler {

    override val spec = McpToolSpec(
        name = "get_todo_monitor_report",
        description = "Return aggregated report for saved periodic todo monitoring runs",
        parameters = McpToolParameters(
            properties = emptyMap(),
            required = emptyList()
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): String {
        val state = TodoMonitorStore().load()
        val runs = state.runs

        if (runs.isEmpty()) {
            return "No monitoring data yet. Schedule the monitor first and wait for at least one run."
        }

        val avgCompleted = runs.map { it.completedCount }.average()
        val lastRun = state.lastRunAtMillis?.let { format(it) } ?: "unknown"
        val last = runs.last()

        val recent = runs.takeLast(3).joinToString("\n") {
            "- ${format(it.timestampMillis)}: ${it.completedCount}/${it.totalCount}, completedIds=${it.completedIds}"
        }

        val avgRounded = round(avgCompleted * 100) / 100

        return buildString {
            appendLine("Todo monitor report")
            appendLine("Runs: ${runs.size}")
            appendLine("Interval hours: ${state.intervalHours}")
            appendLine("Last run: $lastRun")
            appendLine("Last completed count: ${last.completedCount}/${last.totalCount}")
            appendLine("Average completed count: $avgRounded")
            appendLine("Recent runs:")
            append(recent)
        }.trim()
    }

    private fun format(ts: Long): String {
        val dt = Instant.fromEpochMilliseconds(ts)
            .toLocalDateTime(TimeZone.currentSystemDefault())

        return "${dt.date} ${dt.hour}:${dt.minute}:${dt.second}"
    }
}