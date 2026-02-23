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

    var input by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val messages = remember { mutableStateListOf<Pair<String, String>>() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("TrateAI Chat") })

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { (role, text) ->
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
            error?.let { e ->
                item { Text("Error: $e", color = MaterialTheme.colorScheme.error) }
            }
        }

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
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
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
                    val text = input.trim()
                    input = ""
                    messages += "user" to text
                    isLoading = true
                    error = null

                    scope.launch {
                        val started = TimeSource.Monotonic.markNow()
                        try {
                            val result = client.chat(
                                userText = text,
                                temperature = if (selectedModel.supportsTemperature) temperature.toDouble() else null,
                                model = selectedModel.id
                            )

                            val latencyMs = started.elapsedNow().inWholeMilliseconds
                            val usage = result.usage
                            val costUsd = estimateCostUsd(usage, selectedModel)

                            println(
                                "OPENAI " +
                                        "model=${selectedModel.id} " +
                                        "latencyMs=$latencyMs " +
                                        "inputTokens=${usage?.inputTokens} " +
                                        "outputTokens=${usage?.outputTokens} " +
                                        "totalTokens=${usage?.totalTokens} " +
                                        "costUsd=$costUsd"
                            )

                            messages += "assistant" to result.text
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