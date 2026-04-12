package net.brightroom.garage.shared.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class MultiResponse(
    val success: Map<String, JsonElement> = emptyMap(),
    val error: Map<String, String> = emptyMap(),
)
