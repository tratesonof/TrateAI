package com.example.trateai.openai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
    // runtime transcript
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
        val id = "profile_custom_${nowMillis()}"
        val created = draft.copy(
            id = id,
            title = draft.title.trim(),
            isBuiltIn = false
        )
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

    // ===== Invariants =====
    var invariants by mutableStateOf(initial.invariants)

    fun upsertInvariant(oldKey: String? = null, newKey: String, value: String) {
        val normalizedKey = normalizeInvariantKey(newKey)
        val normalizedValue = value.trim()
        if (normalizedKey.isBlank() || normalizedValue.isBlank()) return

        invariants = invariants.toMutableMap().apply {
            if (!oldKey.isNullOrBlank() && oldKey != normalizedKey) {
                remove(oldKey)
            }
            put(normalizedKey, normalizedValue)
        }.toSortedMap()

        persistAll()
        println("INVARIANT_UI upsert key=$normalizedKey")
    }

    fun removeInvariant(key: String) {
        if (!invariants.containsKey(key)) return
        invariants = invariants.toMutableMap().apply { remove(key) }.toSortedMap()
        persistAll()
        println("INVARIANT_UI remove key=$key")
    }

    // ===== Task FSM =====
    var taskFsm by mutableStateOf(initial.taskFsm)
    private var taskStateJob: Job? by mutableStateOf(null)

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
    var isUpdatingFacts by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    // tokens (persisted)
    var lastRequestInputTokens by mutableStateOf(initial.totalInputTokens)
    var lastResponseOutputTokens by mutableStateOf(initial.totalOutputTokens)
    var sessionDialogueTokensTotal by mutableStateOf(initial.totalTokens)

    // audit
    var lastMemoryRouterRaw by mutableStateOf<String?>(null)
    var lastMemoryRouterApplied by mutableStateOf<MemoryRouterResult?>(null)

    private var summarizeJob: Job? by mutableStateOf(null)
    private var memoryJob: Job? by mutableStateOf(null)
    private var requestJob: Job? by mutableStateOf(null)

    private val routerJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    init {
        if (initial.userProfiles.isEmpty()) persistAll()
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
                selectedProfileId = selectedProfileId,

                invariants = invariants,

                taskFsm = taskFsm
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

        val pauseAction = detectPauseResume(userText)
        if (pauseAction != null) {
            taskFsm = taskFsm.copy(isPaused = pauseAction)
            persistAll()
            if (pauseAction) {
                requestJob?.cancel()
                requestJob = null
                isWaitingResponse = false

                messagesUi += "assistant" to "Ок, поставил задачу на паузу. Чтобы продолжить — напиши “resume/продолжай”."
                return
            } else {
                messagesUi += "assistant" to "Продолжаю с текущего состояния без повторных объяснений."
            }
        }

        routeTaskStateAfterUser(userText)
        routeMemoryAfterUser(userText)

        requestJob?.cancel()
        requestJob = scope.launch {
            when (strategy) {
                ContextStrategyType.LEGACY_SUMMARY_WINDOW -> sendLegacy(userText)
                ContextStrategyType.SLIDING_WINDOW -> sendSliding(userText)
                ContextStrategyType.STICKY_FACTS -> sendStickyFacts(userText)
                ContextStrategyType.BRANCHING -> sendBranching(userText)
            }
        }
    }

    private fun routeTaskStateAfterUser(userText: String) {
        if (taskStateJob?.isActive == true) return

        val before = taskFsm
        val ws = workingSummary
        val lastAssistant = messagesUi.lastOrNull { it.first == "assistant" }?.second

        taskStateJob = scope.launch {
            try {
                if (before.isPaused && detectPauseResume(userText) != false) return@launch

                val (upd, usage) = TaskStateRouter.route(
                    client = client,
                    userMessage = userText,
                    assistantMessage = lastAssistant,
                    current = before,
                    workingSummary = ws
                )

                if (upd != null) {
                    val next = applyTaskUpdate(before, upd, userText)
                    if (next != before) {
                        taskFsm = next
                        persistAll()
                        println("TASK_FSM updated phase=${next.phase} paused=${next.isPaused}")
                    }
                }

                sessionDialogueTokensTotal += (usage?.totalTokens ?: 0).toLong()
                persistAll()
            } catch (t: Throwable) {
                println("TASK_FSM router error: ${t.message ?: t}")
            }
        }
    }

    private fun applyTaskUpdate(current: TaskFsmState, upd: TaskStateUpdate, userText: String): TaskFsmState {
        val resume = detectPauseResume(userText) == false
        val pause = detectPauseResume(userText) == true

        val isPaused = when {
            pause -> true
            resume -> false
            upd.isPaused != null -> upd.isPaused
            else -> current.isPaused
        }

        if (isPaused && !resume) {
            return current.copy(
                isPaused = true,
                expectedAction = upd.expectedAction?.trim().orEmpty().ifBlank { current.expectedAction }
            )
        }

        val phase = parseTaskPhase(upd.phase) ?: current.phase
        val step = upd.currentStep?.trim().orEmpty().ifBlank { current.currentStep }
        val exp = upd.expectedAction?.trim().orEmpty().ifBlank { current.expectedAction }

        return current.copy(
            phase = phase,
            currentStep = step,
            expectedAction = exp,
            isPaused = isPaused
        )
    }

    private fun detectPauseResume(text: String): Boolean? {
        val t = text.trim().lowercase()
        val pause = listOf("pause", "пауза", "стоп", "останови", "заморозь")
        val resume = listOf("resume", "continue", "продолжай", "продолжить", "поехали", "давай дальше")
        return when {
            pause.any { t == it || t.startsWith("$it ") } -> true
            resume.any { t == it || t.startsWith("$it ") } -> false
            else -> null
        }
    }

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
                    val workingAfter = applyDelta(workingBefore, result.workingFactsDelta)
                    val longAfter = applyDelta(longBefore, result.longTermDelta)
                    val notesAfter = mergeNotes(notesBefore, result.longTermNotesDelta, limit = 30)

                    val wsAfter = if (!result.workingSummaryDelta.isNullOrBlank()) {
                        result.workingSummaryDelta.trim()
                    } else {
                        wsBefore
                    }

                    workingFacts = workingAfter
                    longTermProfile = longAfter
                    longTermNotes = notesAfter
                    workingSummary = wsAfter

                    println("MEMORY_ROUTER raw=${raw.shrinkForLog(800)}")
                    println("MEMORY_APPLY working: upsert=${result.workingFactsDelta.upsert.keys.sorted()} remove=${result.workingFactsDelta.remove.sorted()}")
                    println("MEMORY_APPLY longTerm: upsert=${result.longTermDelta.upsert.keys.sorted()} remove=${result.longTermDelta.remove.sorted()}")
                    println("MEMORY_APPLY workingSummaryChanged=${!result.workingSummaryDelta.isNullOrBlank()} longTermNotesAdded=${result.longTermNotesDelta.size}")
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

    fun pauseTask() {
        if (taskFsm.isPaused) return

        requestJob?.cancel()
        requestJob = null
        isWaitingResponse = false

        taskFsm = taskFsm.copy(isPaused = true)
        persistAll()
        messagesUi += "assistant" to "Ок, поставил задачу на паузу. Чтобы продолжить — нажми Resume или напиши “resume/продолжай”."
    }

    fun resumeTask() {
        if (!taskFsm.isPaused) return
        taskFsm = taskFsm.copy(isPaused = false)
        persistAll()
        messagesUi += "assistant" to "Продолжаю с текущего состояния без повторных объяснений."
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
                    taskFsm = taskFsm,
                    invariants = invariants,
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

            val guarded = applyInvariantGuard(result.text)

            applyUsageAndLog(
                result = result,
                started = started,
                extraUsage = guarded.usage
            )

            messagesUi += "assistant" to guarded.text

            legacyWindow += ChatMessage("assistant", guarded.text)
            trimToWindow(legacyWindow)
            persistAll()
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
        } finally {
            isWaitingResponse = false
        }
    }

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
                    taskFsm = taskFsm,
                    invariants = invariants,
                    workingFacts = workingFacts,
                    workingSummary = workingSummary,
                    longTermProfile = longTermProfile,
                    longTermNotes = longTermNotes,
                    historyWindow = slidingHistory
                ),
                temperature = if (selectedModel.supportsTemperature) temperature.toDouble() else null,
                model = selectedModel.id
            )

            val guarded = applyInvariantGuard(result.text)

            applyUsageAndLog(
                result = result,
                started = started,
                extraUsage = guarded.usage
            )

            messagesUi += "assistant" to guarded.text

            slidingHistory = (slidingHistory + ChatMessage("assistant", guarded.text)).takeLast(HISTORY_WINDOW_MESSAGES)
            persistAll()
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
        } finally {
            isWaitingResponse = false
        }
    }

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
                    taskFsm = taskFsm,
                    invariants = invariants,
                    workingFacts = workingFacts,
                    workingSummary = workingSummary,
                    longTermProfile = longTermProfile,
                    longTermNotes = longTermNotes,
                    historyWindow = factsHistory
                ),
                temperature = if (selectedModel.supportsTemperature) temperature.toDouble() else null,
                model = selectedModel.id
            )

            val guarded = applyInvariantGuard(result.text)

            applyUsageAndLog(
                result = result,
                started = started,
                extraUsage = guarded.usage
            )

            messagesUi += "assistant" to guarded.text

            factsHistory = (factsHistory + ChatMessage("assistant", guarded.text)).takeLast(HISTORY_WINDOW_MESSAGES)
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

        logRequestSnapshot("BRANCH($currentBranchId)", shortTermCount = newHist.size)

        val profile = selectedProfile()
        isWaitingResponse = true
        val started = TimeSource.Monotonic.markNow()
        try {
            val result = client.chat(
                messages = buildBranchingRequestMessages(
                    profile = profile,
                    taskFsm = taskFsm,
                    invariants = invariants,
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

            val guarded = applyInvariantGuard(result.text)

            applyUsageAndLog(
                result = result,
                started = started,
                extraUsage = guarded.usage
            )

            messagesUi += "assistant" to guarded.text

            val after = (newHist + ChatMessage("assistant", guarded.text)).takeLast(HISTORY_WINDOW_MESSAGES)
            setCurrentBranchState(getCurrentBranch().copy(history = after))
            persistAll()
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
        } finally {
            isWaitingResponse = false
        }
    }

    private suspend fun applyInvariantGuard(answer: String): GuardedAnswer {
        if (invariants.isEmpty()) return GuardedAnswer(text = answer, usage = null)

        val model = "gpt-5-mini"
        val prompt = buildString {
            append("Ты проверяешь ответ ассистента на нарушение инвариантов.\n")
            append("Инварианты обязательны и не могут быть нарушены.\n")
            append("Если ответ нарушает хотя бы один инвариант, нужно это явно отметить и дать безопасную альтернативу.\n\n")

            append("Верни строго JSON без текста вокруг:\n")
            append("{\n")
            append("  \"violates\": true|false,\n")
            append("  \"reason\": \"короткое объяснение\",\n")
            append("  \"violated_invariants\": [\"key1\", \"key2\"],\n")
            append("  \"safe_alternative\": \"безопасная альтернатива, соблюдающая инварианты\" | null\n")
            append("}\n\n")

            append("Инварианты:\n")
            if (invariants.isEmpty()) {
                append("(empty)\n")
            } else {
                invariants.entries.sortedBy { it.key }.forEach { (k, v) ->
                    append("- ").append(k).append(": ").append(v).append("\n")
                }
            }

            append("\nОтвет ассистента:\n")
            append(answer)
        }

        val res = client.chat(
            messages = listOf(
                ChatMessage("system", "Ты — Invariant Guard. Отвечай строго JSON."),
                ChatMessage("user", prompt)
            ),
            temperature = null,
            model = model
        )

        val decision = parseInvariantGuardJson(res.text)
        if (decision == null || !decision.violates) {
            return GuardedAnswer(text = answer, usage = res.usage)
        }

        val violationKeys = decision.violatedInvariants
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val refusal = buildString {
            append("Не могу предложить решение в таком виде, потому что оно нарушает инварианты")
            if (violationKeys.isNotEmpty()) {
                append(": ")
                append(violationKeys.joinToString(", "))
            }
            append(".\n")

            if (decision.reason.isNotBlank()) {
                append("\n")
                append(decision.reason.trim())
                append("\n")
            }

            if (!decision.safeAlternative.isNullOrBlank()) {
                append("\nАльтернатива, которая соблюдает ограничения:\n")
                append(decision.safeAlternative.trim())
            }
        }.trim()

        return GuardedAnswer(text = refusal, usage = res.usage)
    }

    private fun parseInvariantGuardJson(raw: String): InvariantGuardDecision? {
        val t = raw.trim()
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val candidate = t.substring(start, end + 1)
        return runCatching { routerJson.decodeFromString(InvariantGuardDecision.serializer(), candidate) }.getOrNull()
    }

    private fun applyUsageAndLog(
        result: ChatResult,
        started: TimeSource.Monotonic.ValueTimeMark,
        extraUsage: ResponseUsage? = null
    ) {
        lastRequestInputTokens = (result.usage?.inputTokens ?: 0).toLong()
        lastResponseOutputTokens = (result.usage?.outputTokens ?: 0).toLong()

        val requestTokens = (result.usage?.totalTokens ?: 0).toLong()
        val guardTokens = (extraUsage?.totalTokens ?: 0).toLong()
        sessionDialogueTokensTotal += requestTokens + guardTokens

        val latencyMs = started.elapsedNow().inWholeMilliseconds
        println(
            "OPENAI model=${selectedModel.id} latencyMs=$latencyMs " +
                    "lastIn=$lastRequestInputTokens lastOut=$lastResponseOutputTokens " +
                    "sessionTokensTotal=$sessionDialogueTokensTotal " +
                    "guardTokens=$guardTokens"
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
    fun taskPhaseForFooter(): String = taskFsm.phase.asWire()
    fun taskPausedForFooter(): Boolean = taskFsm.isPaused

    private fun logRequestSnapshot(tag: String, shortTermCount: Int) {
        println(
            "MEMORY_REQUEST [$tag] shortTermCount=$shortTermCount " +
                    "workingFactsKeys=${workingFacts.keys.sorted()} " +
                    "longTermKeys=${longTermProfile.keys.sorted()} " +
                    "invariantKeys=${invariants.keys.sorted()} " +
                    "longTermNotesCount=${longTermNotes.size} " +
                    "workingSummaryLen=${workingSummary.length} " +
                    "profileId=$selectedProfileId " +
                    "taskPhase=${taskFsm.phase.asWire()} paused=${taskFsm.isPaused}"
        )
    }
}

@Serializable
private data class InvariantGuardDecision(
    @SerialName("violates") val violates: Boolean = false,
    @SerialName("reason") val reason: String = "",
    @SerialName("violated_invariants") val violatedInvariants: List<String> = emptyList(),
    @SerialName("safe_alternative") val safeAlternative: String? = null
)

private data class GuardedAnswer(
    val text: String,
    val usage: ResponseUsage?
)

private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

private fun normalizeInvariantKey(raw: String): String {
    return raw.trim()
        .lowercase()
        .replace(Regex("[^a-z0-9а-я_\\-\\s]"), "")
        .replace(Regex("[\\s\\-]+"), "_")
        .trim('_')
}

/* ===========================
   Формирование запросов
   =========================== */

private fun buildTaskFsmBlock(taskFsm: TaskFsmState): String = buildString {
    append("Состояние задачи (FSM):\n")
    append("- phase: ").append(taskFsm.phase.asWire()).append("\n")
    append("- current_step: ").append(taskFsm.currentStep.ifBlank { "(empty)" }).append("\n")
    append("- expected_action: ").append(taskFsm.expectedAction.ifBlank { "(empty)" }).append("\n")
    append("- is_paused: ").append(taskFsm.isPaused).append("\n\n")

    append("Правила FSM:\n")
    append("- Если is_paused=true: НЕ продвигай задачу, не повторяй объяснения. Кратко напомни expected_action и попроси resume.\n")
    append("- Если resume: продолжай с текущего состояния без повторных объяснений.\n")
}.trim()

private fun buildMemorySystemBlock(
    profile: UserProfile?,
    taskFsm: TaskFsmState,
    invariants: Map<String, String>,
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

    if (invariants.isNotEmpty()) {
        append("\nИнварианты системы (обязательные ограничения):\n")
        invariants.entries.sortedBy { it.key }.forEach { (k, v) ->
            append("- ").append(k).append(": ").append(v).append("\n")
        }

        append("\nПравила для инвариантов:\n")
        append("- Всегда сначала сверяй решение с этими инвариантами.\n")
        append("- Не предлагай варианты, которые им противоречат.\n")
        append("- Если запрос пользователя конфликтует с инвариантами — прямо откажись от нарушающего варианта.\n")
        append("- После отказа предложи ближайшую допустимую альтернативу.\n")
    }

    append("\n").append(buildTaskFsmBlock(taskFsm)).append("\n")

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
    taskFsm: TaskFsmState,
    invariants: Map<String, String>,
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
                append(buildMemorySystemBlock(profile, taskFsm, invariants, workingFacts, workingSummary, longTermProfile, longTermNotes))
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
    taskFsm: TaskFsmState,
    invariants: Map<String, String>,
    workingFacts: Map<String, String>,
    workingSummary: String,
    longTermProfile: Map<String, String>,
    longTermNotes: List<String>,
    historyWindow: List<ChatMessage>
): List<ChatMessage> = buildList {
    add(ChatMessage("system", buildMemorySystemBlock(profile, taskFsm, invariants, workingFacts, workingSummary, longTermProfile, longTermNotes)))
    addAll(historyWindow)
}

private fun buildStickyFactsRequestMessages(
    profile: UserProfile?,
    taskFsm: TaskFsmState,
    invariants: Map<String, String>,
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
                append(buildMemorySystemBlock(profile, taskFsm, invariants, workingFacts, workingSummary, longTermProfile, longTermNotes))
                append("\n\nПравило: используй рабочие facts/память как источник истины; история — только для локального контекста.\n")
            }.trim()
        )
    )
    addAll(historyWindow)
}

private fun buildBranchingRequestMessages(
    profile: UserProfile?,
    taskFsm: TaskFsmState,
    invariants: Map<String, String>,
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
                append(buildMemorySystemBlock(profile, taskFsm, invariants, workingFacts, workingSummary, longTermProfile, longTermNotes))
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