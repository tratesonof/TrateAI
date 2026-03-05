package com.example.trateai.openai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.time.Clock
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
    // runtime transcript (как и было)
    val messagesUi = mutableStateListOf<Pair<String, String>>()

    // settings
    var temperature by mutableStateOf(0.7f)
    var selectedModel: ModelSpec by mutableStateOf(MODELS[1])
    var strategy by mutableStateOf(initial.strategy)

    // ===== Profiles =====
    var userProfiles by mutableStateOf(ensureProfiles(initial.userProfiles))
    var selectedProfileId by mutableStateOf(
        initial.selectedProfileId.takeIf { id -> userProfiles.any { it.id == id } }
            ?: userProfiles.firstOrNull()?.id
            ?: DEFAULT_PROFILE_FUN
    )

    fun selectedProfile(): UserProfile? = userProfiles.firstOrNull { it.id == selectedProfileId }

    fun setSelectedProfile(id: String) {
        selectedProfileId = id
        persistAll()
        println("PROFILE_UI selected=$id")
    }

    fun createProfile(draft: UserProfile): String {
        val id = "profile_custom_${Clock.System.now()}"
        val created = draft.copy(id = id, isBuiltIn = false)
        upsertProfile(created)
        setSelectedProfile(id)
        return id
    }

    fun upsertProfile(p: UserProfile) {
        userProfiles = userProfiles.toMutableList().apply {
            val idx = indexOfFirst { it.id == p.id }
            if (idx >= 0) set(idx, p) else add(p)
        }.sortedWith(compareBy<UserProfile> { !it.isBuiltIn }.thenBy { it.title })

        if (userProfiles.none { it.id == selectedProfileId }) {
            selectedProfileId = userProfiles.firstOrNull()?.id ?: DEFAULT_PROFILE_FUN
        }
        persistAll()
        println("PROFILE_UI upsert id=${p.id} builtIn=${p.isBuiltIn}")
    }

    fun deleteProfile(id: String) {
        val target = userProfiles.firstOrNull { it.id == id } ?: return
        if (target.isBuiltIn) return

        userProfiles = userProfiles.filterNot { it.id == id }
        if (selectedProfileId == id) {
            selectedProfileId = userProfiles.firstOrNull()?.id ?: DEFAULT_PROFILE_FUN
        }
        persistAll()
        println("PROFILE_UI delete id=$id")
    }

    private fun ensureProfiles(existing: List<UserProfile>): List<UserProfile> {
        val builtIns = defaultProfiles()
        if (existing.isEmpty()) {
            return builtIns.sortedWith(compareBy<UserProfile> { !it.isBuiltIn }.thenBy { it.title })
        }

        val map = existing.associateBy { it.id }.toMutableMap()
        builtIns.forEach { map[it.id] = it }
        return map.values.sortedWith(compareBy<UserProfile> { !it.isBuiltIn }.thenBy { it.title })
    }

    // ===== Memory separation =====
    // short-term (strategy-specific)
    var legacySummary by mutableStateOf(initial.summary)
    val legacyWindow = mutableStateListOf<ChatMessage>().apply { addAll(initial.lastMessages) }

    var slidingHistory by mutableStateOf(initial.slidingHistory)
    var factsHistory by mutableStateOf(initial.factsHistory)

    var currentBranchId by mutableStateOf(initial.branching.currentBranchId)
    var branches by mutableStateOf(initial.branching.branches)
    var checkpoint by mutableStateOf<BranchCheckpoint?>(initial.branching.checkpoint)

    // working memory
    var workingFacts by mutableStateOf(initial.workingFacts)
    var workingSummary by mutableStateOf(initial.workingSummary)

    // long-term memory
    var longTermProfile by mutableStateOf(initial.longTermProfile)
    var longTermNotes by mutableStateOf(initial.longTermNotes)

    // flags
    var isWaitingResponse by mutableStateOf(false)
    var isSummarizing by mutableStateOf(false)
    // UI ранее называлось UpdatingFacts — теперь используем этот флаг как "Routing memory"
    var isUpdatingFacts by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    // tokens (persisted)
    var lastRequestInputTokens by mutableStateOf(initial.totalInputTokens)
    var lastResponseOutputTokens by mutableStateOf(initial.totalOutputTokens)
    var sessionDialogueTokensTotal by mutableStateOf(initial.totalTokens)

    // ===== Debug/Audit: чтобы понимать, что попадает в каждый слой =====
    var lastMemoryRouterRaw by mutableStateOf<String?>(null)
    var lastMemoryRouterApplied by mutableStateOf<MemoryRouterResult?>(null)

    private var summarizeJob: Job? by mutableStateOf(null)
    private var memoryJob: Job? by mutableStateOf(null)

    private val routerJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    init {
        // миграция: если в сохранённом состоянии нет профилей — появятся built-in, и сохраним.
        if (initial.userProfiles.isEmpty()) {
            persistAll()
        }
    }

    fun persistAll() {
        store.save(
            ChatState(
                summary = legacySummary,
                lastMessages = legacyWindow.toList(),

                totalInputTokens = lastRequestInputTokens,
                totalOutputTokens = lastResponseOutputTokens,
                totalTokens = sessionDialogueTokensTotal,

                strategy = strategy,

                slidingHistory = slidingHistory,
                factsHistory = factsHistory,

                workingFacts = workingFacts,
                workingSummary = workingSummary,
                longTermProfile = longTermProfile,
                longTermNotes = longTermNotes,

                branching = BranchingState(
                    currentBranchId = currentBranchId,
                    branches = branches,
                    checkpoint = checkpoint
                ),

                userProfiles = userProfiles,
                selectedProfileId = selectedProfileId
            )
        )
    }

    fun updateStrategy(value: ContextStrategyType) {
        strategy = value
        persistAll()
        println("MEMORY_UI strategy=${value.name}")
    }

    fun getCurrentBranch(): BranchState = branches[currentBranchId] ?: BranchState()

    private fun setCurrentBranchState(newState: BranchState) {
        branches = branches.toMutableMap().apply { put(currentBranchId, newState) }
    }

    fun switchBranch(id: String) {
        currentBranchId = id
        persistAll()
        println("MEMORY_UI switchBranch=$id")
    }

    fun createCheckpoint() {
        checkpoint = BranchCheckpoint(branchId = currentBranchId, state = getCurrentBranch().copy())
        persistAll()
        println("MEMORY_UI checkpoint created for branch=$currentBranchId size=${getCurrentBranch().history.size}")
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
        println("MEMORY_UI fork from checkpoint -> created $aId and $bId (baseSize=${base.history.size})")
    }

    fun createNewBranch(): String {
        val newId = uniqueBranchId(branches, "manual")
        branches = branches.toMutableMap().apply { put(newId, BranchState()) }
        currentBranchId = newId
        persistAll()
        println("MEMORY_UI new branch created=$newId")
        return newId
    }

    fun resetBranching() {
        currentBranchId = "main"
        branches = mapOf("main" to BranchState())
        checkpoint = null
        persistAll()
        println("MEMORY_UI branching reset -> main")
    }

    fun hasCheckpoint(): Boolean = checkpoint != null

    fun send(userText: String) {
        if (userText.isBlank() || isWaitingResponse) return

        error = null
        messagesUi += "user" to userText

        // Явное разнесение: роутим working/long-term отдельно от short-term
        routeMemoryAfterUser(userText)

        scope.launch {
            when (strategy) {
                ContextStrategyType.LEGACY_SUMMARY_WINDOW -> sendLegacy(userText)
                ContextStrategyType.SLIDING_WINDOW -> sendSliding(userText)
                ContextStrategyType.STICKY_FACTS -> sendStickyFacts(userText)
                ContextStrategyType.BRANCHING -> sendBranching(userText)
            }
        }
    }

    // ===== Memory Router: working + long-term (явно отдельно) =====
    private fun routeMemoryAfterUser(userText: String) {
        if (memoryJob?.isActive == true) return

        val workingBefore = workingFacts
        val longBefore = longTermProfile
        val notesBefore = longTermNotes
        val wsBefore = workingSummary

        memoryJob = scope.launch {
            isUpdatingFacts = true
            try {
                val (result, usage, raw) = routeMemoryInternal(
                    userText = userText,
                    workingFacts = workingBefore,
                    longTermProfile = longBefore,
                    longTermNotes = notesBefore
                )

                lastMemoryRouterRaw = raw
                lastMemoryRouterApplied = result

                if (result != null) {
                    // apply deltas explicitly
                    val workingAfter = applyDelta(workingBefore, result.workingFactsDelta)
                    val longAfter = applyDelta(longBefore, result.longTermDelta)
                    val notesAfter = mergeNotes(notesBefore, result.longTermNotesDelta, limit = 30)

                    val wsAfter = if (!result.workingSummaryDelta.isNullOrBlank()) {
                        result.workingSummaryDelta.trim()
                    } else {
                        wsBefore
                    }

                    // assign
                    workingFacts = workingAfter
                    longTermProfile = longAfter
                    longTermNotes = notesAfter
                    workingSummary = wsAfter

                    // ===== AUDIT LOGS =====
                    println("MEMORY_ROUTER raw=${raw.shrinkForLog(800)}")
                    println(
                        "MEMORY_APPLY working: upsert=${result.workingFactsDelta.upsert.keys.sorted()} " +
                                "remove=${result.workingFactsDelta.remove.sorted()}"
                    )
                    println(
                        "MEMORY_APPLY longTerm: upsert=${result.longTermDelta.upsert.keys.sorted()} " +
                                "remove=${result.longTermDelta.remove.sorted()}"
                    )
                    println(
                        "MEMORY_APPLY workingSummaryChanged=${!result.workingSummaryDelta.isNullOrBlank()} " +
                                "longTermNotesAdded=${result.longTermNotesDelta.size}"
                    )
                    println("MEMORY_SNAPSHOT workingFactsKeys=${workingAfter.keys.sorted()}")
                    println("MEMORY_SNAPSHOT longTermKeys=${longAfter.keys.sorted()}")
                    println("MEMORY_SNAPSHOT longTermNotesCount=${notesAfter.size}")
                } else {
                    println("MEMORY_ROUTER parseFailed raw=${raw.shrinkForLog(800)}")
                }

                sessionDialogueTokensTotal += (usage?.totalTokens ?: 0).toLong()
                persistAll()
            } finally {
                isUpdatingFacts = false
            }
        }
    }

    private suspend fun routeMemoryInternal(
        userText: String,
        workingFacts: Map<String, String>,
        longTermProfile: Map<String, String>,
        longTermNotes: List<String>
    ): Triple<MemoryRouterResult?, ResponseUsage?, String> {
        val model = "gpt-5-mini"

        val prompt = buildString {
            append("Ты маршрутизируешь память диалога по 3 типам: short-term, working, long-term.\n")
            append("short-term НЕ трогай (её хранит клиент отдельно).\n")
            append("Верни JSON с изменениями для working и long-term.\n\n")

            append("Формат ответа: строго JSON без текста вокруг:\n")
            append("{\n")
            append("  \"working_facts_delta\": {\"upsert\": {\"key\": \"value\"}, \"remove\": [\"key\"]},\n")
            append("  \"long_term_delta\": {\"upsert\": {\"key\": \"value\"}, \"remove\": [\"key\"]},\n")
            append("  \"working_summary_delta\": \"...\" | null,\n")
            append("  \"long_term_notes_delta\": [\"...\"]\n")
            append("}\n\n")

            append("Правила:\n")
            append("- Working: цель/задача, ограничения, требования, план, прогресс, договорённости ТЕКУЩЕЙ задачи.\n")
            append("- Long-term: стабильные предпочтения/профиль/принципы/повторяющиеся решения.\n")
            append("- Не выдумывай. Если нечего обновлять — пустые deltas.\n")
            append("- Ключи: snake_case, короткие. Значения: 1-2 предложения.\n\n")

            append("Текущие working facts:\n")
            append(workingFacts.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}: ${it.value}" }.ifBlank { "(empty)" })
            append("\n\nТекущий long-term профиль:\n")
            append(longTermProfile.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}: ${it.value}" }.ifBlank { "(empty)" })
            append("\n\nLong-term notes:\n")
            append(longTermNotes.joinToString("\n").ifBlank { "(empty)" })

            append("\n\nНовое сообщение пользователя:\n")
            append(userText)
        }

        val res = client.chat(
            messages = listOf(
                ChatMessage("system", "Ты — Memory Router. Отвечай строго JSON."),
                ChatMessage("user", prompt)
            ),
            temperature = null,
            model = model
        )

        val raw = res.text
        val parsed = parseRouterJson(raw)
        return Triple(parsed, res.usage, raw)
    }

    private fun parseRouterJson(raw: String): MemoryRouterResult? {
        val t = raw.trim()
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val candidate = t.substring(start, end + 1)
        return runCatching { routerJson.decodeFromString(MemoryRouterResult.serializer(), candidate) }.getOrNull()
    }

    // ===== Strategy 0: Legacy summary + window (сохраняем прежнюю логику) =====
    private suspend fun sendLegacy(userText: String) {
        if (legacyWindow.size >= HISTORY_WINDOW_MESSAGES && (summarizeJob?.isActive != true)) {
            val chunk = legacyWindow.toList()
            val summaryAtStart = legacySummary

            legacyWindow.clear()
            persistAll()

            summarizeJob = scope.launch {
                isSummarizing = true
                try {
                    val sumRes = summarizeChunkWithUsage(client, summaryAtStart, chunk)

                    val currentNow = legacySummary
                    legacySummary = if (currentNow == summaryAtStart) {
                        sumRes.text
                    } else {
                        summarizeChunkWithUsage(client, currentNow, chunk).text
                    }

                    // legacy summary логически = рабочий summary текущей задачи
                    workingSummary = legacySummary

                    sessionDialogueTokensTotal += (sumRes.usage?.totalTokens ?: 0).toLong()
                    persistAll()
                } catch (t: Throwable) {
                    println("OPENAI summarize error: ${t.message ?: t}")
                } finally {
                    isSummarizing = false
                }
            }
        }

        legacyWindow += ChatMessage("user", userText)
        trimToWindow(legacyWindow)
        persistAll()

        logRequestSnapshot("LEGACY", shortTermCount = legacyWindow.size)

        val profile = selectedProfile()
        isWaitingResponse = true
        val started = TimeSource.Monotonic.markNow()
        try {
            val result = client.chat(
                messages = buildLegacyRequestMessages(
                    profile = profile,
                    legacySummary = legacySummary,
                    workingFacts = workingFacts,
                    workingSummary = workingSummary,
                    longTermProfile = longTermProfile,
                    longTermNotes = longTermNotes,
                    historyWindow = legacyWindow
                ),
                temperature = if (selectedModel.supportsTemperature) temperature.toDouble() else null,
                model = selectedModel.id
            )

            applyUsageAndLog(result, started)
            messagesUi += "assistant" to result.text

            legacyWindow += ChatMessage("assistant", result.text)
            trimToWindow(legacyWindow)
            persistAll()
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
        } finally {
            isWaitingResponse = false
        }
    }

    // ===== Strategy 1: Sliding window =====
    private suspend fun sendSliding(userText: String) {
        slidingHistory = (slidingHistory + ChatMessage("user", userText)).takeLast(HISTORY_WINDOW_MESSAGES)
        persistAll()

        logRequestSnapshot("SLIDING", shortTermCount = slidingHistory.size)

        val profile = selectedProfile()
        isWaitingResponse = true
        val started = TimeSource.Monotonic.markNow()
        try {
            val result = client.chat(
                messages = buildSlidingRequestMessages(
                    profile = profile,
                    workingFacts = workingFacts,
                    workingSummary = workingSummary,
                    longTermProfile = longTermProfile,
                    longTermNotes = longTermNotes,
                    historyWindow = slidingHistory
                ),
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

    // ===== Strategy 2: Sticky facts =====
    private suspend fun sendStickyFacts(userText: String) {
        factsHistory = (factsHistory + ChatMessage("user", userText)).takeLast(HISTORY_WINDOW_MESSAGES)
        persistAll()

        logRequestSnapshot("FACTS", shortTermCount = factsHistory.size)

        val profile = selectedProfile()
        isWaitingResponse = true
        val started = TimeSource.Monotonic.markNow()
        try {
            val result = client.chat(
                messages = buildStickyFactsRequestMessages(
                    profile = profile,
                    workingFacts = workingFacts,
                    workingSummary = workingSummary,
                    longTermProfile = longTermProfile,
                    longTermNotes = longTermNotes,
                    historyWindow = factsHistory
                ),
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

    // ===== Strategy 3: Branching =====
    private suspend fun sendBranching(userText: String) {
        val branch = getCurrentBranch()
        val newHist = (branch.history + ChatMessage("user", userText)).takeLast(HISTORY_WINDOW_MESSAGES)
        setCurrentBranchState(branch.copy(history = newHist))
        persistAll()

        logRequestSnapshot("BRANCH($currentBranchId)", shortTermCount = newHist.size)

        val profile = selectedProfile()
        isWaitingResponse = true
        val started = TimeSource.Monotonic.markNow()
        try {
            val result = client.chat(
                messages = buildBranchingRequestMessages(
                    profile = profile,
                    branchId = currentBranchId,
                    workingFacts = workingFacts,
                    workingSummary = workingSummary,
                    longTermProfile = longTermProfile,
                    longTermNotes = longTermNotes,
                    historyWindow = newHist
                ),
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
        ContextStrategyType.LEGACY_SUMMARY_WINDOW -> legacyWindow.size
        ContextStrategyType.SLIDING_WINDOW -> slidingHistory.size
        ContextStrategyType.STICKY_FACTS -> factsHistory.size
        ContextStrategyType.BRANCHING -> getCurrentBranch().history.size
    }

    fun factsCountForFooter(): Int = workingFacts.size
    fun branchesCountForFooter(): Int = branches.size

    private fun logRequestSnapshot(tag: String, shortTermCount: Int) {
        println(
            "MEMORY_REQUEST [$tag] shortTermCount=$shortTermCount " +
                    "workingFactsKeys=${workingFacts.keys.sorted()} " +
                    "longTermKeys=${longTermProfile.keys.sorted()} " +
                    "longTermNotesCount=${longTermNotes.size} " +
                    "workingSummaryLen=${workingSummary.length} " +
                    "profileId=$selectedProfileId"
        )
    }
}

/* ===========================
   Формирование запросов
   =========================== */

private fun buildMemorySystemBlock(
    profile: UserProfile?,
    workingFacts: Map<String, String>,
    workingSummary: String,
    longTermProfile: Map<String, String>,
    longTermNotes: List<String>
): String = buildString {
    append("Ты полезный ассистент.\n")

    if (profile != null) {
        append("\nПрофиль пользователя:\n")
        append("- title: ").append(profile.title).append("\n")
        if (profile.style.isNotBlank()) append("- style: ").append(profile.style.trim()).append("\n")
        if (profile.format.isNotBlank()) append("- format: ").append(profile.format.trim()).append("\n")
        if (profile.constraints.isNotBlank()) append("- constraints: ").append(profile.constraints.trim()).append("\n")
        if (profile.systemPrompt.isNotBlank()) {
            append("\nСистемные инструкции профиля:\n")
            append(profile.systemPrompt.trim()).append("\n")
        }
    }

    if (longTermProfile.isNotEmpty() || longTermNotes.isNotEmpty()) {
        append("\nДолговременная память (profile):\n")
        longTermProfile.entries.sortedBy { it.key }.forEach { (k, v) ->
            append("- ").append(k).append(": ").append(v).append("\n")
        }
        if (longTermNotes.isNotEmpty()) {
            append("\nДолговременные заметки:\n")
            longTermNotes.takeLast(10).forEach { note ->
                append("- ").append(note).append("\n")
            }
        }
    }

    if (workingFacts.isNotEmpty() || workingSummary.isNotBlank()) {
        append("\nРабочая память (текущая задача):\n")
        workingFacts.entries.sortedBy { it.key }.forEach { (k, v) ->
            append("- ").append(k).append(": ").append(v).append("\n")
        }
        if (workingSummary.isNotBlank()) {
            append("\nРабочее summary:\n")
            append(workingSummary).append("\n")
        }
    }
}.trim()

private fun buildLegacyRequestMessages(
    profile: UserProfile?,
    legacySummary: String,
    workingFacts: Map<String, String>,
    workingSummary: String,
    longTermProfile: Map<String, String>,
    longTermNotes: List<String>,
    historyWindow: List<ChatMessage>
): List<ChatMessage> = buildList {
    add(
        ChatMessage(
            role = "system",
            content = buildString {
                append(buildMemorySystemBlock(profile, workingFacts, workingSummary, longTermProfile, longTermNotes))
                if (legacySummary.isNotBlank()) {
                    append("\n\nКонтекст (legacy summary):\n")
                    append(legacySummary)
                }
            }.trim()
        )
    )
    addAll(historyWindow)
}

private fun buildSlidingRequestMessages(
    profile: UserProfile?,
    workingFacts: Map<String, String>,
    workingSummary: String,
    longTermProfile: Map<String, String>,
    longTermNotes: List<String>,
    historyWindow: List<ChatMessage>
): List<ChatMessage> = buildList {
    add(ChatMessage("system", buildMemorySystemBlock(profile, workingFacts, workingSummary, longTermProfile, longTermNotes)))
    addAll(historyWindow)
}

private fun buildStickyFactsRequestMessages(
    profile: UserProfile?,
    workingFacts: Map<String, String>,
    workingSummary: String,
    longTermProfile: Map<String, String>,
    longTermNotes: List<String>,
    historyWindow: List<ChatMessage>
): List<ChatMessage> = buildList {
    add(
        ChatMessage(
            "system",
            buildString {
                append(buildMemorySystemBlock(profile, workingFacts, workingSummary, longTermProfile, longTermNotes))
                append("\n\nПравило: используй рабочие facts/память как источник истины; история — только для локального контекста.\n")
            }.trim()
        )
    )
    addAll(historyWindow)
}

private fun buildBranchingRequestMessages(
    profile: UserProfile?,
    branchId: String,
    workingFacts: Map<String, String>,
    workingSummary: String,
    longTermProfile: Map<String, String>,
    longTermNotes: List<String>,
    historyWindow: List<ChatMessage>
): List<ChatMessage> = buildList {
    add(
        ChatMessage(
            "system",
            buildString {
                append(buildMemorySystemBlock(profile, workingFacts, workingSummary, longTermProfile, longTermNotes))
                append("\n\nВетка диалога: ").append(branchId).append("\n")
            }.trim()
        )
    )
    addAll(historyWindow)
}

/* ===========================
   Internal helpers
   =========================== */

private fun trimToWindow(list: MutableList<ChatMessage>) {
    if (list.size <= HISTORY_WINDOW_MESSAGES) return
    repeat(list.size - HISTORY_WINDOW_MESSAGES) { list.removeAt(0) }
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

private fun uniqueBranchId(branches: Map<String, BranchState>, suffix: String): String {
    var i = 1
    while (true) {
        val id = "branch_${suffix}_$i"
        if (!branches.containsKey(id)) return id
        i++
    }
}

private fun String.shrinkForLog(max: Int): String {
    val s = trim()
    if (s.length <= max) return s
    return s.take(max) + "…(truncated ${s.length - max})"
}