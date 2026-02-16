package com.example.trateai.openai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen() {
    val scope = rememberCoroutineScope()

    val client = remember {
        OpenAiClient(
            apiKeyProvider = { API_KEY },
            httpClient = platformHttpClient()
        )
    }

    var input by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val messages = remember { mutableStateListOf<Pair<String, String>>() } // role -> text

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("TrateAI Chat") })

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { (role, text) ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if (role == "user") "You" else "GPT", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(text)
                    }
                }
            }

            if (isLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { e -> item { Text("Error: $e", color = MaterialTheme.colorScheme.error) } }
        }

        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        try {
                            val reply = client.chat(userText = text)
                            messages += "assistant" to reply
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

private const val API_KEY = "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"