package net.brightroom.garage.web.screens.worker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import net.brightroom.garage.web.api.ApiClient
import net.brightroom.garage.web.components.ErrorBanner
import net.brightroom.garage.web.components.LoadingIndicator
import net.brightroom.garage.web.components.StatusChip

@Stable
class WorkerState(private val scope: CoroutineScope) {
    var workers by mutableStateOf<JsonObject?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var busyOnly by mutableStateOf(false)
    var errorOnly by mutableStateOf(false)

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            try {
                val body = buildJsonObject {
                    if (busyOnly) put("busyOnly", true)
                    if (errorOnly) put("errorOnly", true)
                }
                workers = ApiClient.json.decodeFromString<JsonObject>(
                    ApiClient.post("/workers/list", body.toString())
                )
            } catch (e: Exception) {
                error = e.message ?: "Failed to load workers"
            }
            loading = false
        }
    }
}

@Composable
fun rememberWorkerState(): WorkerState {
    val scope = rememberCoroutineScope()
    return remember { WorkerState(scope) }
}

@Composable
fun WorkerScreen() {
    val state = rememberWorkerState()

    LaunchedEffect(state.busyOnly, state.errorOnly) { state.refresh() }

    WorkerContent(
        workers = state.workers,
        error = state.error,
        loading = state.loading,
        busyOnly = state.busyOnly,
        errorOnly = state.errorOnly,
        onBusyOnlyChange = { state.busyOnly = it },
        onErrorOnlyChange = { state.errorOnly = it },
        onRefresh = state::refresh,
    )
}

@Composable
fun WorkerContent(
    workers: JsonObject?,
    error: String?,
    loading: Boolean,
    busyOnly: Boolean,
    errorOnly: Boolean,
    modifier: Modifier = Modifier,
    onBusyOnlyChange: (Boolean) -> Unit,
    onErrorOnlyChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Workers", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onRefresh) { Text("Refresh") }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = busyOnly, onCheckedChange = onBusyOnlyChange)
                Text("Busy only")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = errorOnly, onCheckedChange = onErrorOnlyChange)
                Text("Errors only")
            }
        }

        Spacer(Modifier.height(16.dp))
        error?.let { ErrorBanner(it) }

        if (loading && workers == null) {
            LoadingIndicator()
            return
        }

        workers?.get("success")?.jsonObject?.forEach { (nodeId, value) ->
            Text(
                "Node: ${nodeId.take(12)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            val workerList = value.jsonArray
            if (workerList.isEmpty()) {
                Text("No workers", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            workerList.forEach { workerJson ->
                val w = workerJson.jsonObject
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                w["name"]?.jsonPrimitive?.content ?: "Unknown",
                                fontWeight = FontWeight.Medium,
                            )
                            val errors = w["errors"]?.jsonPrimitive?.long ?: 0
                            if (errors > 0) {
                                Text(
                                    "Errors: $errors",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            w["progress"]?.jsonPrimitive?.content?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        val state = w["state"]
                        val stateText = when {
                            state is JsonPrimitive -> state.content
                            state is JsonObject -> state.keys.firstOrNull() ?: "unknown"
                            else -> "unknown"
                        }
                        StatusChip(
                            when (stateText) {
                                "idle" -> "healthy"
                                "busy" -> "degraded"
                                else -> stateText
                            }
                        )
                    }
                }
            }
        }
    }
}
