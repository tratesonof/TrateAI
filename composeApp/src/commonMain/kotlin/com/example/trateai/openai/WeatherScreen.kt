package com.example.trateai.openai

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.trateai.openai.mcp.WeatherMcpClient
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeatherScreen(
    onBack: () -> Unit
) {
    var city by remember { mutableStateOf("Москва") }
    var weather by remember { mutableStateOf<String?>(null) }
    var airQuality by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var isConnected by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()
    val client = remember { WeatherMcpClient() }
    
    LaunchedEffect(Unit) {
        scope.launch {
            isLoading = true
            try {
                val initResult = client.initialize()
                if (initResult.isSuccess) {
                    val toolsResult = client.listTools()
                    toolsResult.onSuccess { tools ->
                        println("MCP Tools loaded: ${tools.size}")
                        isConnected = true
                    }
                } else {
                }
            } catch (e: Exception) {
                error = e.message
            }
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Weather MCP") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("← Back") }
                },
                actions = {
                    if (isConnected) {
                        Text("●", color = androidx.compose.ui.graphics.Color.Green)
                    } else if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedTextField(
                value = city,
                onValueChange = { city = it },
                label = { Text("City") },
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            error = null
                            weather = null
                            
                            // MOCK: для тестирования
                            val cityLower = city.lowercase()
                            if (cityLower.contains("москв") || cityLower.contains("moscow")) {
                                weather = "Moscow: 6°C\nPartly cloudy\nFeels like: 4°C\nHumidity: 72%\nWind: 12 km/h"
                            } else {
                                val result = client.getWeatherByCity(city)
                                result.onSuccess { weather = it }
                                    .onFailure { error = it.message }
                            }
                            
                            isLoading = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading
                ) {
                    Text(if (isLoading) "..." else "Weather")
                }
                
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            error = null
                            airQuality = null
                            
                            // MOCK: для тестирования
                            val cityLower = city.lowercase()
                            if (cityLower.contains("москв") || cityLower.contains("moscow")) {
                                airQuality = "Moscow Air Quality: Good\nPM2.5: 12 μg/m³\nPM10: 25 μg/m³"
                            } else {
                                val result = client.getAirQuality(city)
                                result.onSuccess { airQuality = it }
                                    .onFailure { error = it.message }
                            }
                            
                            isLoading = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading
                ) {
                    Text(if (isLoading) "..." else "Air")
                }
            }
            
            error?.let {
                Text(
                    text = "Error: $it",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }
            
            weather?.let {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Weather:", style = MaterialTheme.typography.titleMedium)
                        Text(it, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            
            airQuality?.let {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Air Quality:", style = MaterialTheme.typography.titleMedium)
                        Text(it, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            client.close()
        }
    }
}