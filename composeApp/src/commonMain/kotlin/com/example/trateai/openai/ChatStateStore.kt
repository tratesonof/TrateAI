package com.example.trateai.openai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val KEY_CHAT_STATE = "chat_state_v2"

@Serializable
data class ChatState(
    val summary: String = "",
    val lastMessages: List<ChatMessage> = emptyList(),

    val totalInputTokens: Long = 0,
    val totalOutputTokens: Long = 0,
    val totalTokens: Long = 0,

    val strategy: ContextStrategyType = ContextStrategyType.LEGACY_SUMMARY_WINDOW,

    val slidingHistory: List<ChatMessage> = emptyList(),

    val workingFacts: Map<String, String> = emptyMap(),
    val workingSummary: String = "",

    val longTermProfile: Map<String, String> = emptyMap(),
    val longTermNotes: List<String> = emptyList(),

    val factsHistory: List<ChatMessage> = emptyList(),

    val branching: BranchingState = BranchingState(),

    val userProfiles: List<UserProfile> = emptyList(),
    val selectedProfileId: String = DEFAULT_PROFILE_FUN
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

@Serializable
data class UserProfile(
    val id: String,
    val title: String,
    val style: String = "",
    val format: String = "",
    val constraints: String = "",
    val systemPrompt: String = "",
    val isBuiltIn: Boolean = false
)

const val DEFAULT_PROFILE_FUN = "profile_fun"
const val DEFAULT_PROFILE_FACTS = "profile_facts"
const val DEFAULT_PROFILE_CLARIFY = "profile_clarify"

fun defaultProfiles(): List<UserProfile> = listOf(
    UserProfile(
        id = DEFAULT_PROFILE_FUN,
        title = "Весёлый 😄",
        style = "Дружелюбный, лёгкий, позитивный тон.",
        format = "Коротко, по делу. Можно списками.",
        constraints = "Добавляй уместные эмодзи. Не перегружай.",
        systemPrompt = "Пиши дружелюбно, допускаются эмодзи. Без токсичности, без лишней болтовни.",
        isBuiltIn = true
    ),
    UserProfile(
        id = DEFAULT_PROFILE_FACTS,
        title = "Только факты",
        style = "Нейтральный, безэмоциональный тон.",
        format = "Структура: факты → выводы (если нужны) → шаги.",
        constraints = "Без эмоций, без оценочных суждений, без метафор.",
        systemPrompt = "Отвечай строго фактически. Без эмоций и без субъективных оценок. Если данных недостаточно — перечисли, чего не хватает.",
        isBuiltIn = true
    ),
    UserProfile(
        id = DEFAULT_PROFILE_CLARIFY,
        title = "Уточняющий",
        style = "Нейтрально, прагматично.",
        format = "Сначала 1 уточняющий вопрос, затем краткий ответ на основе допущений.",
        constraints = "Всегда задай минимум 1 уточняющий вопрос перед тем, как предлагать финальное решение.",
        systemPrompt = "Перед финальным ответом задай минимум один уточняющий вопрос. Если можешь — параллельно дай краткий ответ с допущениями.",
        isBuiltIn = true
    )
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