package com.example.trateai.openai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val KEY_CHAT_STATE = "chat_state_v2"

@Serializable
data class ChatState(
    // Legacy summary strategy storage
    val summary: String = "",
    val lastMessages: List<ChatMessage> = emptyList(), // legacy window

    // Counters (persisted)
    val totalInputTokens: Long = 0,
    val totalOutputTokens: Long = 0,
    val totalTokens: Long = 0,

    // New: strategy selection
    val strategy: ContextStrategyType = ContextStrategyType.LEGACY_SUMMARY_WINDOW,

    // New: Sliding storage
    val slidingHistory: List<ChatMessage> = emptyList(),

    // New: Sticky Facts storage
    val facts: Map<String, String> = emptyMap(),
    val factsHistory: List<ChatMessage> = emptyList(),

    // New: Branching storage
    val branching: BranchingState = BranchingState()
)

@Serializable
enum class ContextStrategyType(val title: String) {
    LEGACY_SUMMARY_WINDOW("Summary"),
    SLIDING_WINDOW("Sliding"),
    STICKY_FACTS("Facts"),
    BRANCHING("Branch")
}

@Serializable
data class BranchState(
    val history: List<ChatMessage> = emptyList()
)

@Serializable
data class BranchCheckpoint(
    val branchId: String = "main",
    val state: BranchState = BranchState()
)

@Serializable
data class BranchingState(
    val currentBranchId: String = "main",
    val branches: Map<String, BranchState> = mapOf("main" to BranchState()),
    val checkpoint: BranchCheckpoint? = null
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