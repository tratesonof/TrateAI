package com.example.trateai.openai.mcp

interface McpToolHandler {
    val spec: McpToolSpec

    suspend fun execute(arguments: Map<String, Any>): String
}