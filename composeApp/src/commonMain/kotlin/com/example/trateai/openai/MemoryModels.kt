package com.example.trateai.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class KvDelta(
    @SerialName("upsert") val upsert: Map<String, String> = emptyMap(),
    @SerialName("remove") val remove: List<String> = emptyList()
)

@Serializable
data class MemoryRouterResult(
    @SerialName("working_facts_delta") val workingFactsDelta: KvDelta = KvDelta(),
    @SerialName("long_term_delta") val longTermDelta: KvDelta = KvDelta(),
    @SerialName("working_summary_delta") val workingSummaryDelta: String? = null,
    @SerialName("long_term_notes_delta") val longTermNotesDelta: List<String> = emptyList()
)

internal fun applyDelta(base: Map<String, String>, delta: KvDelta): Map<String, String> {
    val m = base.toMutableMap()
    delta.remove.forEach { m.remove(it) }
    delta.upsert.forEach { (k, v) ->
        val kk = k.trim()
        val vv = v.trim()
        if (kk.isNotBlank() && vv.isNotBlank()) m[kk] = vv
    }
    return m
}

internal fun mergeNotes(base: List<String>, add: List<String>, limit: Int = 30): List<String> {
    val out = (base + add.map { it.trim() }.filter { it.isNotBlank() })
        .distinct()
        .takeLast(limit)
    return out
}