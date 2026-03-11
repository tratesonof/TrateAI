package com.example.trateai.openai.mcp

class McpToolRegistry(
    handlers: List<McpToolHandler>
) {
    private val handlersByName = handlers.associateBy { it.spec.name }

    fun definitions() = handlersByName.values.map { handler ->
        com.example.trateai.openai.ToolDefinition(
            name = handler.spec.name,
            description = handler.spec.description,
            parameters = com.example.trateai.openai.ToolParameters(
                properties = handler.spec.parameters.properties.mapValues { (_, p) ->
                    com.example.trateai.openai.ToolProperty(
                        type = p.type,
                        description = p.description
                    )
                },
                required = handler.spec.parameters.required
            )
        )
    }

    suspend fun execute(name: String, arguments: Map<String, Any>): String {
        val handler = handlersByName[name] ?: return "Unknown tool: $name"
        return handler.execute(arguments)
    }
}