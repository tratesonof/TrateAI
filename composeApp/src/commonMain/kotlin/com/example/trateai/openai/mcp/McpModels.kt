package com.example.trateai.openai.mcp

import kotlinx.serialization.Serializable

@Serializable
data class McpToolSpec(
    val name: String,
    val description: String,
    val parameters: McpToolParameters
)

@Serializable
data class McpToolParameters(
    val properties: Map<String, McpToolProperty>,
    val required: List<String> = emptyList()
)

@Serializable
data class McpToolProperty(
    val type: String,
    val description: String
)