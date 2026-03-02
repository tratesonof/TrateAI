package com.example.trateai.openai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    controller: ChatController,
    onBack: () -> Unit
) {
    var modelMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Context strategy", style = MaterialTheme.typography.titleMedium)
                        StrategySelector(
                            value = controller.strategy,
                            onChange = { controller.updateStrategy(it) }
                        )
                    }
                }
            }

            item {
                ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Model", style = MaterialTheme.typography.titleMedium)

                        ExposedDropdownMenuBox(
                            expanded = modelMenuExpanded,
                            onExpandedChange = { modelMenuExpanded = !modelMenuExpanded }
                        ) {
                            OutlinedTextField(
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                value = controller.selectedModel.title,
                                onValueChange = {},
                                readOnly = true,
                                singleLine = true,
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded)
                                }
                            )

                            ExposedDropdownMenu(
                                expanded = modelMenuExpanded,
                                onDismissRequest = { modelMenuExpanded = false }
                            ) {
                                MODELS.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(m.title) },
                                        onClick = {
                                            controller.selectedModel = m
                                            modelMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        Text(
                            text = if (controller.selectedModel.supportsTemperature)
                                "Temperature: ${roundTo2(controller.temperature)}"
                            else
                                "Temperature: not supported for this model",
                            style = MaterialTheme.typography.labelMedium
                        )

                        Slider(
                            enabled = controller.selectedModel.supportsTemperature,
                            value = controller.temperature,
                            onValueChange = { controller.temperature = it },
                            valueRange = 0f..2f,
                            steps = 20
                        )
                    }
                }
            }

            if (controller.strategy == ContextStrategyType.BRANCHING) {
                item {
                    ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Branching", style = MaterialTheme.typography.titleMedium)

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                var expanded by remember { mutableStateOf(false) }
                                val ids = controller.branches.keys.sorted()

                                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                                    OutlinedTextField(
                                        modifier = Modifier.menuAnchor().weight(1f),
                                        value = controller.currentBranchId,
                                        onValueChange = {},
                                        readOnly = true,
                                        singleLine = true,
                                        trailingIcon = {
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                                        }
                                    )
                                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                        ids.forEach { id ->
                                            DropdownMenuItem(
                                                text = { Text(id, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                                onClick = {
                                                    expanded = false
                                                    controller.switchBranch(id)
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { controller.createCheckpoint() }) { Text("Checkpoint") }
                                Button(
                                    enabled = controller.hasCheckpoint(),
                                    onClick = { controller.forkFromCheckpointTwoBranches() }
                                ) { Text("Fork x2") }
                                OutlinedButton(onClick = { controller.resetBranching() }) { Text("Reset") }
                            }

                            Text(
                                text = if (controller.hasCheckpoint())
                                    "Checkpoint saved. Fork is available."
                                else
                                    "Create a checkpoint to fork 2 branches.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StrategySelector(
    value: ContextStrategyType,
    onChange: (ContextStrategyType) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ContextStrategyType.values().forEach { t ->
            FilterChip(
                selected = value == t,
                onClick = { onChange(t) },
                label = { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            )
        }
    }
}