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
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.round
import kotlin.time.TimeSource

private const val HISTORY_WINDOW_MESSAGES = 6

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
    var isWaitingResponse by remember { mutableStateOf(false) }
    var isSummarizing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val messagesUi = remember { mutableStateListOf<Pair<String, String>>() }

    var summary by remember { mutableStateOf(loaded.summary) }
    val history = remember { mutableStateListOf<ChatMessage>().apply { addAll(loaded.lastMessages) } } // <= 6 сообщений

    // tokens
    var lastRequestInputTokens by remember { mutableStateOf(0L) }   // только для последнего запроса
    var lastResponseOutputTokens by remember { mutableStateOf(0L) } // только для последнего ответа
    var sessionDialogueTokensTotal by remember { mutableStateOf(0L) } // накопительно за сессию (включая суммаризацию)

    // чтобы не запускать несколько суммаризаций одновременно
    var summarizeJob by remember { mutableStateOf<Job?>(null) }

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

            if (isWaitingResponse) {
                item {
                    Column {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Waiting for model response…", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (isSummarizing) {
                item {
                    Column {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Summarizing history…", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

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

        // Temperature
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
                .padding(12.dp)
        ) {
            Text(
                "Last request • in: $lastRequestInputTokens  out: $lastResponseOutputTokens",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Session tokens total: $sessionDialogueTokensTotal",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "History window: ${history.size}/$HISTORY_WINDOW_MESSAGES",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Input
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
                placeholder = { Text("Message…") }
            )
            Button(
                enabled = input.isNotBlank() && !isWaitingResponse,
                onClick = {
                    val userText = input.trim()
                    input = ""
                    error = null

                    messagesUi += "user" to userText

                    scope.launch {
                        // 1) Если окно истории заполнено (6 сообщений) — запускаем суммаризацию ПАРАЛЛЕЛЬНО,
                        //    а историю ресетим сразу, чтобы начать новый цикл.
                        if (history.size >= HISTORY_WINDOW_MESSAGES && (summarizeJob?.isActive != true)) {
                            val chunk = history.toList()
                            val summaryAtStart = summary

                            // reset history immediately (per requirement) and persist
                            history.clear()
                            store.save(ChatState(summary = summary, lastMessages = history.toList()))

                            summarizeJob = launch {
                                isSummarizing = true
                                try {
                                    val sumRes = summarizeChunkWithUsage(
                                        client = client,
                                        currentSummary = summaryAtStart,
                                        chunk = chunk
                                    )

                                    // важно: summary мог измениться позже — мы не хотим "затереть" новое.
                                    // поэтому делаем merge: обновляем summary на базе текущего на момент завершения.
                                    // самый простой лаконичный способ: ещё раз прогнать суммаризацию на (currentSummaryNow + chunk)
                                    // мы уже сделали, поэтому просто применим результат, но только если summary не изменялся.
                                    // если изменялся — делаем вторую быструю суммаризацию (редко).
                                    val currentNow = summary
                                    summary = if (currentNow == summaryAtStart) {
                                        sumRes.text
                                    } else {
                                        // summary уже обновлялся чем-то ещё — пересобираем итог
                                        summarizeChunkWithUsage(
                                            client = client,
                                            currentSummary = currentNow,
                                            chunk = chunk
                                        ).text
                                    }

                                    store.save(ChatState(summary = summary, lastMessages = history.toList()))

                                    // токены суммаризации учитываем в session total
                                    val sumTotal = (sumRes.usage?.totalTokens ?: 0).toLong()
                                    sessionDialogueTokensTotal += sumTotal

                                    println("OPENAI summarize tokensTotal=$sumTotal sessionTokensTotal=$sessionDialogueTokensTotal")
                                } catch (t: Throwable) {
                                    println("OPENAI summarize error: ${t.message ?: t}")
                                } finally {
                                    isSummarizing = false
                                }
                            }
                        }

                        // 2) Добавляем текущее user-сообщение в НОВОЕ окно истории и сохраняем
                        history += ChatMessage(role = "user", content = userText)
                        trimHistoryToWindow(history)
                        store.save(ChatState(summary = summary, lastMessages = history.toList()))

                        // 3) Основной запрос (ждём ответ). Суммаризация при этом может идти параллельно.
                        isWaitingResponse = true
                        val started = TimeSource.Monotonic.markNow()

                        try {
                            val result = client.chat(
                                messages = buildRequestMessages(summary, history),
                                temperature = if (selectedModel.supportsTemperature) temperature.toDouble() else null,
                                model = selectedModel.id
                            )

                            lastRequestInputTokens = (result.usage?.inputTokens ?: 0).toLong()
                            lastResponseOutputTokens = (result.usage?.outputTokens ?: 0).toLong()
                            sessionDialogueTokensTotal += (result.usage?.totalTokens ?: 0).toLong()

                            val latencyMs = started.elapsedNow().inWholeMilliseconds
                            println(
                                "OPENAI model=${selectedModel.id} latencyMs=$latencyMs " +
                                        "lastIn=$lastRequestInputTokens lastOut=$lastResponseOutputTokens " +
                                        "sessionTokensTotal=$sessionDialogueTokensTotal"
                            )

                            messagesUi += "assistant" to result.text

                            // 4) Добавляем ответ в окно истории и сохраняем
                            history += ChatMessage(role = "assistant", content = result.text)
                            trimHistoryToWindow(history)
                            store.save(ChatState(summary = summary, lastMessages = history.toList()))
                        } catch (t: Throwable) {
                            error = t.message ?: t.toString()
                        } finally {
                            isWaitingResponse = false
                        }
                    }
                }
            ) { Text("Send") }
        }
    }
}

private fun buildRequestMessages(summary: String, historyWindow: List<ChatMessage>): List<ChatMessage> =
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

private fun trimHistoryToWindow(history: MutableList<ChatMessage>) {
    if (history.size <= HISTORY_WINDOW_MESSAGES) return
    repeat(history.size - HISTORY_WINDOW_MESSAGES) { history.removeAt(0) }
}

private data class SummaryResult(val text: String, val usage: ResponseUsage?)

/**
 * Суммаризуем ровно "предыдущее окно" (6 сообщений) + текущий summary.
 * По требованию: при 7-м сообщении мы запускаем это параллельно и ресетим историю.
 */
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