package com.example.trateai.openai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val KEY_CHAT_STATE = "chat_state_v1"

@Serializable
data class ChatState(
    val summary: String = "",
    val lastMessages: List<ChatMessage> = emptyList(),

    val totalInputTokens: Long = 0,
    val totalOutputTokens: Long = 0,
    val totalTokens: Long = 0,
)

class ChatStateStore(
    private val kv: KvStore = createKvStore()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun load(): ChatState {
        val raw = kv.getString(KEY_CHAT_STATE) ?: return ChatState()
        if (raw.isBlank()) return ChatState()
        return runCatching { json.decodeFromString(ChatState.serializer(), raw) }
            .getOrElse { ChatState() }
    }

    fun save(state: ChatState) {
        kv.putString(KEY_CHAT_STATE, json.encodeToString(ChatState.serializer(), state))
    }

    fun clear() {
        kv.remove(KEY_CHAT_STATE)
    }
}

interface KvStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

expect fun createKvStore(): KvStore