package com.example.trateai.openai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
                        Text("User profile", style = MaterialTheme.typography.titleMedium)

                        var expanded by remember { mutableStateOf(false) }
                        var showCreate by remember { mutableStateOf(false) }
                        var showEdit by remember { mutableStateOf(false) }

                        val profiles = controller.userProfiles
                        val selected = controller.selectedProfile()

                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = !expanded }
                        ) {
                            OutlinedTextField(
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth(),
                                value = selected?.title.orEmpty(),
                                onValueChange = {},
                                readOnly = true,
                                singleLine = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
                            )

                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                profiles.forEach { p ->
                                    DropdownMenuItem(
                                        text = { Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        onClick = {
                                            expanded = false
                                            controller.setSelectedProfile(p.id)
                                        }
                                    )
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { showCreate = true }) { Text("Create") }
                            OutlinedButton(
                                enabled = selected != null,
                                onClick = { showEdit = true }
                            ) { Text("Edit") }
                            OutlinedButton(
                                enabled = selected?.isBuiltIn == false,
                                onClick = { selected?.let { controller.deleteProfile(it.id) } }
                            ) { Text("Delete") }
                        }

                        Text(
                            text = "Профиль прокидывается в каждый запрос (system prompt).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (showCreate) {
                            ProfileEditorDialog(
                                title = "Create profile",
                                initial = UserProfile(
                                    id = "tmp",
                                    title = "",
                                    isBuiltIn = false
                                ),
                                confirmText = "Create",
                                onDismiss = { showCreate = false },
                                onConfirm = { draft ->
                                    controller.createProfile(draft)
                                    showCreate = false
                                }
                            )
                        }

                        if (showEdit && selected != null) {
                            ProfileEditorDialog(
                                title = "Edit profile",
                                initial = selected,
                                confirmText = "Save",
                                onDismiss = { showEdit = false },
                                onConfirm = { draft ->
                                    if (selected.isBuiltIn) {
                                        controller.createProfile(draft.copy(id = "tmp", isBuiltIn = false))
                                    } else {
                                        controller.upsertProfile(draft.copy(id = selected.id, isBuiltIn = false))
                                        controller.setSelectedProfile(selected.id)
                                    }
                                    showEdit = false
                                }
                            )
                        }
                    }
                }
            }

            item {
                ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Invariants", style = MaterialTheme.typography.titleMedium)

                        Text(
                            text = "Отдельный слой ограничений, который всегда прокидывается в model prompt. Ассистент не должен предлагать решения, которые их нарушают.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        var showCreate by remember { mutableStateOf(false) }
                        var editingKey by remember { mutableStateOf<String?>(null) }

                        Button(onClick = { showCreate = true }) {
                            Text("Add invariant")
                        }

                        InvariantPresetChips(
                            onAdd = { key, value ->
                                controller.upsertInvariant(
                                    oldKey = null,
                                    newKey = key,
                                    value = value
                                )
                            }
                        )

                        if (controller.invariants.isEmpty()) {
                            Text(
                                text = "Инварианты пока не заданы.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                controller.invariants.entries.sortedBy { it.key }.forEach { (key, value) ->
                                    ElevatedCard(shape = RoundedCornerShape(12.dp)) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = key,
                                                style = MaterialTheme.typography.labelLarge
                                            )
                                            Text(
                                                text = value,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedButton(
                                                    onClick = { editingKey = key }
                                                ) { Text("Edit") }

                                                OutlinedButton(
                                                    onClick = { controller.removeInvariant(key) }
                                                ) { Text("Delete") }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Text(
                            text = "Примеры: architecture, technical_decisions, stack_constraints, business_rules.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (showCreate) {
                            InvariantEditorDialog(
                                title = "Create invariant",
                                initialKey = "",
                                initialValue = "",
                                confirmText = "Create",
                                onDismiss = { showCreate = false },
                                onConfirm = { key, value ->
                                    controller.upsertInvariant(
                                        oldKey = null,
                                        newKey = key,
                                        value = value
                                    )
                                    showCreate = false
                                }
                            )
                        }

                        if (editingKey != null) {
                            val currentKey = editingKey.orEmpty()
                            val currentValue = controller.invariants[currentKey].orEmpty()

                            InvariantEditorDialog(
                                title = "Edit invariant",
                                initialKey = currentKey,
                                initialValue = currentValue,
                                confirmText = "Save",
                                onDismiss = { editingKey = null },
                                onConfirm = { newKey, newValue ->
                                    controller.upsertInvariant(
                                        oldKey = currentKey,
                                        newKey = newKey,
                                        value = newValue
                                    )
                                    editingKey = null
                                }
                            )
                        }
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
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth(),
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
                                        modifier = Modifier
                                            .menuAnchor()
                                            .weight(1f),
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

@Composable
private fun InvariantPresetChips(
    onAdd: (String, String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Quick presets",
            style = MaterialTheme.typography.labelMedium
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = false,
                onClick = {
                    onAdd("architecture", "Использовать только MVVM + Clean Architecture.")
                },
                label = { Text("Architecture") }
            )
            FilterChip(
                selected = false,
                onClick = {
                    onAdd("technical_decisions", "Использовать coroutines + Flow. Избегать legacy callback API там, где есть suspend/Flow.")
                },
                label = { Text("Tech") }
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = false,
                onClick = {
                    onAdd("stack_constraints", "Kotlin, Jetpack Compose, Ktor. Не предлагать Retrofit, XML и RxJava.")
                },
                label = { Text("Stack") }
            )
            FilterChip(
                selected = false,
                onClick = {
                    onAdd("business_rules", "Нельзя предлагать решения, нарушающие бизнес-правила или обязательные продуктовые ограничения.")
                },
                label = { Text("Business") }
            )
        }
    }
}

@Composable
private fun ProfileEditorDialog(
    title: String,
    initial: UserProfile,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (UserProfile) -> Unit
) {
    var t by remember { mutableStateOf(initial.title) }
    var style by remember { mutableStateOf(initial.style) }
    var format by remember { mutableStateOf(initial.format) }
    var constraints by remember { mutableStateOf(initial.constraints) }
    var systemPrompt by remember { mutableStateOf(initial.systemPrompt) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = t,
                    onValueChange = { t = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = style,
                    onValueChange = { style = it },
                    label = { Text("Style") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = format,
                    onValueChange = { format = it },
                    label = { Text("Format") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = constraints,
                    onValueChange = { constraints = it },
                    label = { Text("Constraints") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text("System prompt") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = t.isNotBlank(),
                onClick = {
                    onConfirm(
                        initial.copy(
                            title = t.trim(),
                            style = style.trim(),
                            format = format.trim(),
                            constraints = constraints.trim(),
                            systemPrompt = systemPrompt.trim(),
                            isBuiltIn = initial.isBuiltIn
                        )
                    )
                }
            ) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun InvariantEditorDialog(
    title: String,
    initialKey: String,
    initialValue: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var key by remember { mutableStateOf(initialKey) }
    var value by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("Key") },
                    placeholder = { Text("architecture") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Value") },
                    placeholder = { Text("Использовать только MVVM + Clean Architecture.") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = key.isNotBlank() && value.isNotBlank(),
                onClick = { onConfirm(key.trim(), value.trim()) }
            ) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}