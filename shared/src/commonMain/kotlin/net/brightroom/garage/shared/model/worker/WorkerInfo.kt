package net.brightroom.garage.shared.model.worker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class WorkerInfo(
    val id: Long,
    val name: String,
    val state: JsonElement,
    val errors: Long,
    val consecutiveErrors: Long,
    val freeform: List<String>,
    val lastError: WorkerLastError? = null,
    val persistentErrors: Long? = null,
    val progress: String? = null,
    val queueLength: Long? = null,
    val tranquility: Int? = null,
)

@Serializable
data class WorkerLastError(
    val message: String,
    val secsAgo: Long,
)

@Serializable
data class ListWorkersRequest(
    val busyOnly: Boolean? = null,
    val errorOnly: Boolean? = null,
)

@Serializable
data class GetWorkerVariableRequest(
    val variable: String? = null,
)

@Serializable
data class SetWorkerVariableRequest(
    val variable: String,
    val value: String,
)

@Serializable
data class SetWorkerVariableResponse(
    val variable: String,
    val value: String,
)
