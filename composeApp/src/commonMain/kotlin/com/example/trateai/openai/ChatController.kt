package com.example.trateai.openai

import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

private const val HISTORY_WINDOW_MESSAGES = 6

@Composable
fun rememberChatController(): ChatController {
    val scope = rememberCoroutineScope()

    val client = remember {
        OpenAiClient(
            apiKeyProvider = { openAiApiKey() },
            httpClient = platformHttpClient()
        )
    }
    val store = remember { ChatStateStore() }
    val loaded = remember { store.load() }

    return remember {
        ChatController(
            scope = scope,
            client = client,
            store = store,
            initial = loaded
        )
    }
}

class ChatController(
    private val scope: CoroutineScope,
    private val client: OpenAiClient,
    private val store: ChatStateStore,
    initial: ChatState
) {
    val messagesUi = mutableStateListOf<Pair<String, String>>()

    var temperature by mutableStateOf(0.7f)
    var selectedModel: ModelSpec by mutableStateOf(MODELS[1])

    var strategy by mutableStateOf(initial.strategy)

    var summary by mutableStateOf(initial.summary)
    val history = mutableStateListOf<ChatMessage>().apply { addAll(initial.lastMessages) }

    var slidingHistory by mutableStateOf(initial.slidingHistory)

    var facts by mutableStateOf(initial.facts)
    var factsHistory by mutableStateOf(initial.factsHistory)

    var currentBranchId by mutableStateOf(initial.branching.currentBranchId)
    var branches by mutableStateOf(initial.branching.branches)
    var checkpoint by mutableStateOf<BranchCheckpoint?>(initial.branching.checkpoint)

    var isWaitingResponse by mutableStateOf(false)
    var isSummarizing by mutableStateOf(false)
    var isUpdatingFacts by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    var lastRequestInputTokens by mutableStateOf(initial.totalInputTokens)
    var lastResponseOutputTokens by mutableStateOf(initial.totalOutputTokens)
    var sessionDialogueTokensTotal by mutableStateOf(initial.totalTokens)

    private var summarizeJob: Job? by mutableStateOf(null)
    private var factsJob: Job? by mutableStateOf(null)

    fun persistAll() {
        store.save(
            ChatState(
                summary = summary,
                lastMessages = history.toList(),
                totalInputTokens = lastRequestInputTokens,
                totalOutputTokens = lastResponseOutputTokens,
                totalTokens = sessionDialogueTokensTotal,
                strategy = strategy,
                slidingHistory = slidingHistory,
                facts = facts,
                factsHistory = factsHistory,
                branching = BranchingState(
                    currentBranchId = currentBranchId,
                    branches = branches,
                    checkpoint = checkpoint
                )
            )
        )
    }

    fun getCurrentBranch(): BranchState = branches[currentBranchId] ?: BranchState()

    private fun setCurrentBranchState(newState: BranchState) {
        branches = branches.toMutableMap().apply { put(currentBranchId, newState) }
    }

    fun updateStrategy(value: ContextStrategyType) {
        strategy = value
        persistAll()
    }

    fun switchBranch(id: String) {
        currentBranchId = id
        persistAll()
    }

    fun createCheckpoint() {
        checkpoint = BranchCheckpoint(branchId = currentBranchId, state = getCurrentBranch().copy())
        persistAll()
    }

    fun forkFromCheckpointTwoBranches() {
        val cp = checkpoint ?: return
        val base = cp.state
        val aId = uniqueBranchId(branches, "A")
        val bId = uniqueBranchId(branches, "B")
        branches = branches.toMutableMap().apply {
            put(aId, base.copy())
            put(bId, base.copy())
        }
        persistAll()
    }

    fun resetBranching() {
        currentBranchId = "main"
        branches = mapOf("main" to BranchState())
        checkpoint = null
        persistAll()
    }

    fun hasCheckpoint(): Boolean = checkpoint != null

    fun send(userText: String) {
        if (userText.isBlank() || isWaitingResponse) return
        error = null
        messagesUi += "user" to userText

        scope.launch {
            when (strategy) {
                ContextStrategyType.LEGACY_SUMMARY_WINDOW -> sendLegacy(userText)
                ContextStrategyType.SLIDING_WINDOW -> sendSliding(userText)
                ContextStrategyType.STICKY_FACTS -> sendFacts(userText)
                ContextStrategyType.BRANCHING -> sendBranching(userText)
            }
        }
    }

    private suspend fun sendLegacy(userText: String) {
        if (history.size >= HISTORY_WINDOW_MESSAGES && (summarizeJob?.isActive != true)) {
            val chunk = history.toList()
            val summaryAtStart = summary

            history.clear()
            persistAll()

            summarizeJob = scope.launch {
                isSummarizing = true
                try {
                    val sumRes = summarizeChunkWithUsage(client, summaryAtStart, chunk)

                    val currentNow = summary
                    summary = if (currentNow == summaryAtStart) {
                        sumRes.text
                    } else {
                        summarizeChunkWithUsage(client, currentNow, chunk).text
                    }

                    val sumTotal = (sumRes.usage?.totalTokens ?: 0).toLong()
                    sessionDialogueTokensTotal += sumTotal
                    persistAll()

                    println("OPENAI summarize tokensTotal=$sumTotal sessionTokensTotal=$sessionDialogueTokensTotal")
                } catch (t: Throwable) {
                    println("OPENAI summarize error: ${t.message ?: t}")
                } finally {
                    isSummarizing = false
                }
            }
        }

        history += ChatMessage("user", userText)
        trimHistoryToWindow(history)
        persistAll()

        isWaitingResponse = true
        val started = TimeSource.Monotonic.markNow()

        try {
            val result = client.chat(
                messages = buildLegacyRequestMessages(summary, history),
                temperature = if (selectedModel.supportsTemperature) temperature.toDouble() else null,
                model = selectedModel.id
            )

            applyUsageAndLog(result, started)
            messagesUi += "assistant" to result.text

            history += ChatMessage("assistant", result.text)
            trimHistoryToWindow(history)
            persistAll()
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
        } finally {
            isWaitingResponse = false
        }
    }

    private suspend fun sendSliding(userText: String) {
        slidingHistory = (slidingHistory + ChatMessage("user", userText)).takeLast(HISTORY_WINDOW_MESSAGES)
        persistAll()

        isWaitingResponse = true
        val started = TimeSource.Monotonic.markNow()

        try {
            val result = client.chat(
                messages = buildSlidingRequestMessages(slidingHistory),
                temperature = if (selectedModel.supportsTemperature) temperature.toDouble() else null,
                model = selectedModel.id
            )

            applyUsageAndLog(result, started)
            messagesUi += "assistant" to result.text

            slidingHistory = (slidingHistory + ChatMessage("assistant", result.text)).takeLast(HISTORY_WINDOW_MESSAGES)
            persistAll()
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
        } finally {
            isWaitingResponse = false
        }
    }

    private suspend fun sendFacts(userText: String) {
        if (factsJob?.isActive != true) {
            factsJob = scope.launch {
                isUpdatingFacts = true
                try {
                    val upd = updateFactsWithUsage(client, facts, userText)
                    facts = upd.facts
                    sessionDialogueTokensTotal += (upd.usage?.totalTokens ?: 0).toLong()
                    persistAll()
                } catch (_: Throwable) {
                } finally {
                    isUpdatingFacts = false
                }
            }
        }

        factsHistory = (factsHistory + ChatMessage("user", userText)).takeLast(HISTORY_WINDOW_MESSAGES)
        persistAll()

        isWaitingResponse = true
        val started = TimeSource.Monotonic.markNow()

        try {
            val result = client.chat(
                messages = buildFactsRequestMessages(facts, factsHistory),
                temperature = if (selectedModel.supportsTemperature) temperature.toDouble() else null,
                model = selectedModel.id
            )

            applyUsageAndLog(result, started)
            messagesUi += "assistant" to result.text

            factsHistory = (factsHistory + ChatMessage("assistant", result.text)).takeLast(HISTORY_WINDOW_MESSAGES)
            persistAll()
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
        } finally {
            isWaitingResponse = false
        }
    }

    private suspend fun sendBranching(userText: String) {
        val branch = getCurrentBranch()
        val newHist = (branch.history + ChatMessage("user", userText)).takeLast(HISTORY_WINDOW_MESSAGES)
        setCurrentBranchState(branch.copy(history = newHist))
        persistAll()

        isWaitingResponse = true
        val started = TimeSource.Monotonic.markNow()

        try {
            val result = client.chat(
                messages = buildBranchingRequestMessages(currentBranchId, newHist),
                temperature = if (selectedModel.supportsTemperature) temperature.toDouble() else null,
                model = selectedModel.id
            )

            applyUsageAndLog(result, started)
            messagesUi += "assistant" to result.text

            val after = (newHist + ChatMessage("assistant", result.text)).takeLast(HISTORY_WINDOW_MESSAGES)
            setCurrentBranchState(getCurrentBranch().copy(history = after))
            persistAll()
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
        } finally {
            isWaitingResponse = false
        }
    }

    private fun applyUsageAndLog(result: ChatResult, started: TimeSource.Monotonic.ValueTimeMark) {
        lastRequestInputTokens = (result.usage?.inputTokens ?: 0).toLong()
        lastResponseOutputTokens = (result.usage?.outputTokens ?: 0).toLong()
        sessionDialogueTokensTotal += (result.usage?.totalTokens ?: 0).toLong()

        val latencyMs = started.elapsedNow().inWholeMilliseconds
        println(
            "OPENAI model=${selectedModel.id} latencyMs=$latencyMs " +
                    "lastIn=$lastRequestInputTokens lastOut=$lastResponseOutputTokens " +
                    "sessionTokensTotal=$sessionDialogueTokensTotal"
        )
    }

    fun historySizeForFooter(): Int = when (strategy) {
        ContextStrategyType.LEGACY_SUMMARY_WINDOW -> history.size
        ContextStrategyType.SLIDING_WINDOW -> slidingHistory.size
        ContextStrategyType.STICKY_FACTS -> factsHistory.size
        ContextStrategyType.BRANCHING -> getCurrentBranch().history.size
    }

    fun factsCountForFooter(): Int = facts.size
    fun branchesCountForFooter(): Int = branches.size
}

/** Helpers */

private fun buildLegacyRequestMessages(summary: String, historyWindow: List<ChatMessage>): List<ChatMessage> =
    buildList {
        add(
            ChatMessage(
                role = "system",
                content = buildString {
                    append("Ты полезный ассистент. Отвечай кратко и по делу.\n")
                    if (summary.isNotBlank()) {
                        append("\nКонтекст (summary):\n")
                        append(summary)
                    }
                }
            )
        )
        addAll(historyWindow)
    }

private fun buildSlidingRequestMessages(historyWindow: List<ChatMessage>): List<ChatMessage> =
    buildList {
        add(ChatMessage("system", "Ты полезный ассистент. Отвечай кратко и по делу."))
        addAll(historyWindow)
    }

private fun buildFactsRequestMessages(facts: Map<String, String>, historyWindow: List<ChatMessage>): List<ChatMessage> =
    buildList {
        add(
            ChatMessage(
                "system",
                buildString {
                    append("Ты полезный ассистент. Отвечай кратко и по делу.\n")
                    if (facts.isNotEmpty()) {
                        append("\nFacts (key-value memory):\n")
                        facts.entries.sortedBy { it.key }.forEach { (k, v) ->
                            append("- ").append(k).append(": ").append(v).append("\n")
                        }
                    }
                }.trim()
            )
        )
        addAll(historyWindow)
    }

private fun buildBranchingRequestMessages(branchId: String, historyWindow: List<ChatMessage>): List<ChatMessage> =
    buildList {
        add(ChatMessage("system", "Ты полезный ассистент. Ветка диалога: $branchId. Отвечай кратко и по делу."))
        addAll(historyWindow)
    }

private fun trimHistoryToWindow(history: MutableList<ChatMessage>) {
    if (history.size <= HISTORY_WINDOW_MESSAGES) return
    repeat(history.size - HISTORY_WINDOW_MESSAGES) { history.removeAt(0) }
}

private data class SummaryResult(val text: String, val usage: ResponseUsage?)

private suspend fun summarizeChunkWithUsage(
    client: OpenAiClient,
    currentSummary: String,
    chunk: List<ChatMessage>
): SummaryResult {
    val summarizerModel = "gpt-5-mini"

    val prompt = buildString {
        append("Обнови краткий контекст диалога.\n")
        append("Верни summary на русском в виде буллетов.\n")
        append("Сохраняй: факты, предпочтения пользователя, задачи, принятые решения, ограничения.\n")
        append("Не добавляй выдумок.\n")
        append("Длина: до 1200 символов.\n")
        if (currentSummary.isNotBlank()) {
            append("\nТекущее summary:\n")
            append(currentSummary)
        }
        append("\n\nНовый фрагмент диалога:\n")
        chunk.forEach { m -> append("- ${m.role}: ${m.content}\n") }
    }

    val res = client.chat(
        messages = listOf(
            ChatMessage(role = "system", content = "Ты сжимаешь переписку в компактный контекст."),
            ChatMessage(role = "user", content = prompt)
        ),
        temperature = null,
        model = summarizerModel
    )

    return SummaryResult(text = res.text.trim(), usage = res.usage)
}

private data class FactsUpdateResult(val facts: Map<String, String>, val usage: ResponseUsage?)

private suspend fun updateFactsWithUsage(
    client: OpenAiClient,
    currentFacts: Map<String, String>,
    lastUserMessage: String
): FactsUpdateResult {
    val model = "gpt-5-mini"
    val prompt = buildString {
        append("Обнови key-value память facts на основе нового сообщения пользователя.\n")
        append("Верни строго JSON без текста вокруг в формате:\n")
        append("{\"facts\": {\"key\": \"value\", ...}}\n")
        append("Правила:\n")
        append("- ключи короткие (snake_case)\n")
        append("- значения короткие (1-2 предложения)\n")
        append("- не выдумывай\n")
        append("- если факт устарел — перезапиши\n")
        append("- если факт не важен — не добавляй\n\n")
        append("Текущие facts:\n")
        append(currentFacts.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}: ${it.value}" }.ifBlank { "(empty)" })
        append("\n\nНовое сообщение пользователя:\n")
        append(lastUserMessage)
    }

    val res = client.chat(
        messages = listOf(
            ChatMessage("system", "Ты ведёшь компактную память фактов в формате key-value."),
            ChatMessage("user", prompt)
        ),
        temperature = null,
        model = model
    )

    val parsed = FactsJsonParser.tryParseFacts(res.text) ?: currentFacts
    return FactsUpdateResult(facts = parsed, usage = res.usage)
}

private fun uniqueBranchId(branches: Map<String, BranchState>, suffix: String): String {
    var i = 1
    while (true) {
        val id = "branch_$suffix$i"
        if (!branches.containsKey(id)) return id
        i++
    }
}