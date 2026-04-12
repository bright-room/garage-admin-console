package net.brightroom.garage.shared.model.node

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class NodeInfo(
    val nodeId: String,
    val garageVersion: String,
    val rustVersion: String,
    val dbEngine: String,
    val garageFeatures: List<String>? = null,
)

@Serializable
data class NodeStatistics(
    val freeform: String,
)

@Serializable
data class RepairRequest(
    val repair: JsonElement,
)

@Serializable
data class ConnectNodeRequest(
    val addresses: List<String>,
)

@Serializable
data class ConnectNodeResponse(
    val success: Boolean,
    val error: String? = null,
)
