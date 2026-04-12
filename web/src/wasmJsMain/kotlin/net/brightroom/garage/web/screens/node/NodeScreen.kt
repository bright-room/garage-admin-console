package net.brightroom.garage.web.screens.node

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import net.brightroom.garage.web.api.ApiClient
import net.brightroom.garage.web.components.ErrorBanner
import net.brightroom.garage.web.components.LoadingIndicator

@Composable
fun NodeScreen() {
    var nodeInfo by remember { mutableStateOf<JsonObject?>(null) }
    var statistics by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var showRepairDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun loadData() {
        scope.launch {
            loading = true
            error = null
            try {
                nodeInfo = ApiClient.json.decodeFromString<JsonObject>(ApiClient.get("/nodes/info"))
                val statsResponse = ApiClient.json.decodeFromString<JsonObject>(ApiClient.get("/nodes/statistics"))
                // Extract freeform text from the success responses
                val successMap = statsResponse["success"]?.jsonObject
                statistics = successMap?.values?.firstOrNull()?.let { value ->
                    value.jsonObject["freeform"]?.jsonPrimitive?.content
                }
            } catch (e: Exception) {
                error = e.message ?: "Failed to load node info"
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { loadData() }

    if (loading && nodeInfo == null) {
        LoadingIndicator()
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Nodes", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        try {
                            ApiClient.post("/nodes/snapshot", "{}")
                            actionMessage = "Metadata snapshot created"
                        } catch (e: Exception) {
                            error = "Snapshot failed: ${e.message}"
                        }
                    }
                }) { Text("Snapshot") }
                OutlinedButton(onClick = { showRepairDialog = true }) { Text("Repair") }
                OutlinedButton(onClick = { loadData() }) { Text("Refresh") }
            }
        }

        Spacer(Modifier.height(16.dp))
        error?.let { ErrorBanner(it) }
        actionMessage?.let {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(it, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    TextButton(onClick = { actionMessage = null }) { Text("Dismiss") }
                }
            }
        }

        // Node info from success map
        nodeInfo?.get("success")?.jsonObject?.forEach { (nodeId, value) ->
            val info = value.jsonObject
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Node: ${nodeId.take(12)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    InfoRow("Garage Version", info["garageVersion"]?.jsonPrimitive?.content ?: "N/A")
                    InfoRow("Rust Version", info["rustVersion"]?.jsonPrimitive?.content ?: "N/A")
                    InfoRow("DB Engine", info["dbEngine"]?.jsonPrimitive?.content ?: "N/A")
                    val features = info["garageFeatures"]?.jsonArray?.map { it.jsonPrimitive.content }
                    if (!features.isNullOrEmpty()) {
                        InfoRow("Features", features.joinToString(", "))
                    }
                }
            }
        }

        // Statistics
        statistics?.let { stats ->
            Spacer(Modifier.height(16.dp))
            Text("Statistics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stats,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    if (showRepairDialog) {
        RepairDialog(
            onDismiss = { showRepairDialog = false },
            onRepair = { repairType ->
                scope.launch {
                    try {
                        ApiClient.post("/nodes/repair", """{"repair":"$repairType"}""")
                        actionMessage = "Repair operation '$repairType' launched"
                        showRepairDialog = false
                    } catch (e: Exception) {
                        error = "Repair failed: ${e.message}"
                    }
                }
            },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text("$label: ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
