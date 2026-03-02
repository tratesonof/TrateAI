package com.example.trateai.openai

import kotlin.math.round

data class ModelSpec(
    val id: String,
    val title: String,
    val inputUsdPer1M: Double,
    val outputUsdPer1M: Double,
    val supportsTemperature: Boolean,
)

val MODELS: List<ModelSpec> = listOf(
    ModelSpec(
        id = "gpt-5-nano",
        title = "Weak (gpt-5-nano)",
        inputUsdPer1M = 0.05,
        outputUsdPer1M = 0.40,
        supportsTemperature = false
    ),
    ModelSpec(
        id = "gpt-5-mini",
        title = "Medium (gpt-5-mini)",
        inputUsdPer1M = 0.25,
        outputUsdPer1M = 2.00,
        supportsTemperature = false
    ),
    ModelSpec(
        id = "gpt-5.2",
        title = "Strong (gpt-5.2)",
        inputUsdPer1M = 1.75,
        outputUsdPer1M = 14.00,
        supportsTemperature = true
    ),
)

fun roundTo2(v: Float): Float = round(v * 100f) / 100f