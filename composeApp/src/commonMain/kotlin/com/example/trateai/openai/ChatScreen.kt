package com.example.trateai.openai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    controller: ChatController,
    onOpenSettings: () -> Unit,
    onOpenWeather: () -> Unit
) {
    var input by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = { Text("TrateAI Chat") },
            actions = {
                IconButton(onClick = onOpenWeather) {
                    Icon(Icons.Filled.Cloud, contentDescription = "Weather")
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            }
        )

        Divider()

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(controller.messagesUi) { (role, text) ->
                ChatBubble(role = role, text = text)
            }

            if (controller.isWaitingResponse) item { StatusRow("Waiting for model response…") }
            if (controller.isSummarizing) item { StatusRow("Summarizing history…") }
            if (controller.isUpdatingFacts) item { StatusRow("Updating facts…") }

            controller.error?.let { e ->
                item {
                    Text(
                        "Error: $e",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        Divider()

        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
        ) {
            FooterStatsCard(
                lastIn = controller.lastRequestInputTokens,
                lastOut = controller.lastResponseOutputTokens,
                sessionTotal = controller.sessionDialogueTokensTotal,
                historySize = controller.historySizeForFooter(),
                historyWindow = 6,
                strategy = controller.strategy,
                factsCount = controller.factsCountForFooter(),
                branchesCount = controller.branchesCountForFooter(),
                taskPhase = controller.taskPhaseForFooter(),
                taskPaused = controller.taskPausedForFooter(),
                onPause = { controller.pauseTask() },
                onResume = { controller.resumeTask() }
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    placeholder = { Text("Message…", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )

                Button(
                    enabled = input.isNotBlank() && !controller.isWaitingResponse,
                    onClick = {
                        val text = input.trim()
                        input = ""
                        controller.send(text)
                    }
                ) { Text("Send") }
            }
        }
    }
}

@Composable
private fun FooterStatsCard(
    lastIn: Long,
    lastOut: Long,
    sessionTotal: Long,
    historySize: Int,
    historyWindow: Int,
    strategy: ContextStrategyType,
    factsCount: Int,
    branchesCount: Int,
    taskPhase: String,
    taskPaused: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = {}, label = { Text("Last • in $lastIn / out $lastOut") })
                AssistChip(onClick = {}, label = { Text("Session $sessionTotal") })
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = {}, label = { Text("${strategy.title} • History $historySize/$historyWindow") })
                when (strategy) {
                    ContextStrategyType.STICKY_FACTS -> AssistChip(onClick = {}, label = { Text("Facts $factsCount") })
                    ContextStrategyType.BRANCHING -> AssistChip(onClick = {}, label = { Text("Branches $branchesCount") })
                    else -> Unit
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(onClick = {}, label = { Text("Task • $taskPhase") })
                if (taskPaused) {
                    AssistChip(onClick = {}, label = { Text("Paused") })
                }
                Spacer(Modifier.weight(1f))

                OutlinedButton(
                    enabled = !taskPaused,
                    onClick = onPause
                ) { Text("Pause") }

                Button(
                    enabled = taskPaused,
                    onClick = onResume
                ) { Text("Resume") }
            }
        }
    }
}

@Composable
private fun ChatBubble(role: String, text: String) {
    val isUser = role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(Modifier.padding(12.dp).widthIn(max = 520.dp)) {
                Text(
                    text = if (isUser) "You" else "GPT",
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.height(4.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun StatusRow(text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}