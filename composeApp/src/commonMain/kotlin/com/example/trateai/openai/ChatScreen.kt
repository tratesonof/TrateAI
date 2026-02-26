package com.example.trateai.openai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.round
import kotlin.time.TimeSource

private const val LAST_TURNS = 6

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen() {
    val scope = rememberCoroutineScope()

    var temperature by remember { mutableStateOf(0.7f) }

    var selectedModel by remember { mutableStateOf(MODELS[1]) }
    var modelMenuExpanded by remember { mutableStateOf(false) }

    val client = remember {
        OpenAiClient(
            apiKeyProvider = { openAiApiKey() },
            httpClient = platformHttpClient()
        )
    }

    val store = remember { ChatStateStore() }
    val loaded = remember { store.load() }

    var input by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val messagesUi = remember { mutableStateListOf<Pair<String, String>>() }

    var summary by remember { mutableStateOf(loaded.summary) }
    val lastMessages = remember { mutableStateListOf<ChatMessage>().apply { addAll(loaded.lastMessages) } }

    // ===== Token counters (session-only) =====
    // Last request only:
    var lastRequestInputTokens by remember { mutableStateOf(0L) }   // input ONLY for the last request
    var lastResponseOutputTokens by remember { mutableStateOf(0L) } // output ONLY for the last response

    // Whole dialogue within app session:
    var sessionDialogueTokensTotal by remember { mutableStateOf(0L) } // sum(total_tokens) per chat call (incl. summarization)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("TrateAI Chat") })

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messagesUi) { (role, text) ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = if (role == "user") "You" else "GPT",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(text)
                    }
                }
            }

            if (isLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { e -> item { Text("Error: $e", color = MaterialTheme.colorScheme.error) } }
        }

        // Model selector
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        ) {
            Text("Model", style = MaterialTheme.typography.labelMedium)

            ExposedDropdownMenuBox(
                expanded = modelMenuExpanded,
                onExpandedChange = { modelMenuExpanded = !modelMenuExpanded }
            ) {
                OutlinedTextField(
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    value = selectedModel.title,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded) }
                )

                ExposedDropdownMenu(
                    expanded = modelMenuExpanded,
                    onDismissRequest = { modelMenuExpanded = false }
                ) {
                    MODELS.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(m.title) },
                            onClick = {
                                selectedModel = m
                                modelMenuExpanded = false
                            }
                        )
                    }
                }
            }
        }

        // Temperature slider
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        ) {
            Text(
                text = if (selectedModel.supportsTemperature)
                    "Temperature: ${roundTo2(temperature)}"
                else
                    "Temperature: not supported for this model",
                style = MaterialTheme.typography.labelMedium
            )

            Slider(
                enabled = selectedModel.supportsTemperature,
                value = temperature,
                onValueChange = { temperature = it },
                valueRange = 0f..2f,
                steps = 20
            )
        }

        // Footer stats
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                "Last request • in: $lastRequestInputTokens  out: $lastResponseOutputTokens",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Session dialogue tokens total: $sessionDialogueTokensTotal",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Input row
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                modifier = Modifier.weight(1f),
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                placeholder = { Text("Сообщение…") }
            )
            Button(
                enabled = input.isNotBlank() && !isLoading,
                onClick = {
                    val userText = input.trim()
                    input = ""

                    messagesUi += "user" to userText

                    lastMessages += ChatMessage(role = "user", content = userText)
                    trimLastMessagesInPlace(lastMessages)
                    store.save(ChatState(summary = summary, lastMessages = lastMessages.toList()))

                    isLoading = true
                    error = null

                    scope.launch {
                        val started = TimeSource.Monotonic.markNow()
                        try {
                            val requestMessages = buildRequestMessages(summary, lastMessages)

                            val result = client.chat(
                                messages = requestMessages,
                                temperature = if (selectedModel.supportsTemperature) temperature.toDouble() else null,
                                model = selectedModel.id
                            )

                            // ===== last request only =====
                            lastRequestInputTokens = (result.usage?.inputTokens ?: 0).toLong()
                            lastResponseOutputTokens = (result.usage?.outputTokens ?: 0).toLong()

                            // ===== whole dialogue within session =====
                            val reqTotal = (result.usage?.totalTokens
                                ?: (lastRequestInputTokens + lastResponseOutputTokens).toInt()
                                    ).toLong()
                            sessionDialogueTokensTotal += reqTotal

                            val latencyMs = started.elapsedNow().inWholeMilliseconds
                            val costUsd = estimateCostUsd(result.usage, selectedModel)

                            println(
                                "OPENAI " +
                                        "model=${selectedModel.id} " +
                                        "latencyMs=$latencyMs " +
                                        "lastReqInputTokens=$lastRequestInputTokens " +
                                        "lastRespOutputTokens=$lastResponseOutputTokens " +
                                        "sessionDialogueTokensTotal=$sessionDialogueTokensTotal " +
                                        "costUsd=$costUsd"
                            )

                            messagesUi += "assistant" to result.text

                            lastMessages += ChatMessage(role = "assistant", content = result.text)
                            trimLastMessagesInPlace(lastMessages)
                            store.save(ChatState(summary = summary, lastMessages = lastMessages.toList()))

                        } catch (t: Throwable) {
                            error = t.message ?: t.toString()
                        } finally {
                            isLoading = false
                        }
                    }
                }
            ) { Text("Send") }
        }
    }
}

private fun buildRequestMessages(
    summary: String,
    lastMessages: List<ChatMessage>
): List<ChatMessage> {
    val system = buildString {
        append("Ты полезный ассистент. Отвечай кратко и по делу.\n")
        append("Если в диалоге уже есть договорённости/факты — соблюдай их.\n")
        if (summary.isNotBlank()) {
            append("\nКонтекст (summary):\n")
            append(summary)
        }
    }

    return buildList {
        add(ChatMessage(role = "system", content = system))
        addAll(lastMessages)
    }
}

private fun trimLastMessagesInPlace(list: MutableList<ChatMessage>) {
    val maxMessages = LAST_TURNS * 2
    if (list.size <= maxMessages) return
    val toRemove = list.size - maxMessages
    repeat(toRemove) { list.removeAt(0) }
}

private fun shouldSummarize(summary: String, lastMessages: List<ChatMessage>): Boolean {
    if (lastMessages.size < LAST_TURNS * 2) return false
    return summary.length < 120 || (summary.length < 2000 && lastMessages.size == LAST_TURNS * 2)
}

private data class SummaryResult(
    val text: String,
    val usage: ResponseUsage?
)

private suspend fun summarizeLocallyWithUsage(
    client: OpenAiClient,
    currentSummary: String,
    recentMessages: List<ChatMessage>
): SummaryResult {
    val summarizerModel = "gpt-5-mini"

    val summaryPrompt = buildString {
        append("Сжми контекст диалога.\n")
        append("Верни краткое summary на русском в виде буллетов.\n")
        append("Сохраняй: факты, предпочтения пользователя, задачи, принятые решения, ограничения.\n")
        append("Не добавляй выдумок.\n")
        append("Длина: до 1200 символов.\n")
        if (currentSummary.isNotBlank()) {
            append("\nТекущее summary:\n")
            append(currentSummary)
        }
        append("\n\nПоследние сообщения:\n")
        recentMessages.forEach { m ->
            append("- ${m.role}: ${m.content}\n")
        }
    }

    val request = listOf(
        ChatMessage(role = "system", content = "Ты сжимаешь историю переписки в компактный контекст."),
        ChatMessage(role = "user", content = summaryPrompt)
    )

    val res = client.chat(
        messages = request,
        temperature = null,
        model = summarizerModel
    )

    return SummaryResult(text = res.text.trim(), usage = res.usage)
}

private data class ModelSpec(
    val id: String,
    val title: String,
    val inputUsdPer1M: Double,
    val outputUsdPer1M: Double,
    val supportsTemperature: Boolean,
)

private val MODELS = listOf(
    ModelSpec("gpt-5-nano", "Weak (gpt-5-nano)", inputUsdPer1M = 0.05, outputUsdPer1M = 0.40, supportsTemperature = false),
    ModelSpec("gpt-5-mini", "Medium (gpt-5-mini)", inputUsdPer1M = 0.25, outputUsdPer1M = 2.00, supportsTemperature = false),
    ModelSpec("gpt-5.2", "Strong (gpt-5.2)", inputUsdPer1M = 1.75, outputUsdPer1M = 14.00, supportsTemperature = true),
)

private fun roundTo2(v: Float): Float = round(v * 100f) / 100f

private fun estimateCostUsd(
    usage: ResponseUsage?,
    model: ModelSpec
): Double {
    if (usage == null) return 0.0
    val inCost = (usage.inputTokens / 1_000_000.0) * model.inputUsdPer1M
    val outCost = (usage.outputTokens / 1_000_000.0) * model.outputUsdPer1M
    return inCost + outCost
}