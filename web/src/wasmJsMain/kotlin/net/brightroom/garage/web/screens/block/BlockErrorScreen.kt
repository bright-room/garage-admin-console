package net.brightroom.garage.web.screens.block

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
import net.brightroom.garage.shared.model.block.BlockError
import net.brightroom.garage.web.api.ApiClient
import net.brightroom.garage.web.components.ConfirmDialog
import net.brightroom.garage.web.components.ErrorBanner
import net.brightroom.garage.web.components.LoadingIndicator

@Composable
fun BlockErrorScreen() {
    var blockErrors by remember { mutableStateOf<Map<String, List<BlockError>>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var showPurgeDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun loadData() {
        scope.launch {
            loading = true
            error = null
            try {
                val response = ApiClient.json.decodeFromString<JsonObject>(ApiClient.get("/blocks/errors"))
                val successMap = response["success"]?.jsonObject ?: emptyMap()
                blockErrors = successMap.mapValues { (_, value) ->
                    ApiClient.json.decodeFromString<List<BlockError>>(value.toString())
                }
            } catch (e: Exception) {
                error = e.message ?: "Failed to load block errors"
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { loadData() }

    if (loading && blockErrors.isEmpty()) {
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
            Text("Block Errors", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        try {
                            ApiClient.post("/blocks/retry-resync", """{"all":true}""")
                            actionMessage = "Retry resync for all blocks queued"
                            loadData()
                        } catch (e: Exception) {
                            error = "Retry failed: ${e.message}"
                        }
                    }
                }) { Text("Retry All") }
                OutlinedButton(
                    onClick = { showPurgeDialog = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Purge") }
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

        val totalErrors = blockErrors.values.sumOf { it.size }
        if (totalErrors == 0) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("No block errors", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            }
        }

        blockErrors.forEach { (nodeId, errors) ->
            if (errors.isNotEmpty()) {
                Text(
                    "Node: ${nodeId.take(12)} (${errors.size} errors)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 8.dp),
                )

                errors.forEach { blockError ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    blockError.blockHash.take(16) + "...",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "Refs: ${blockError.refcount} | Errors: ${blockError.errorCount}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    "Last try: ${blockError.lastTrySecsAgo}s ago",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "Next try: ${blockError.nextTryInSecs}s",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPurgeDialog) {
        ConfirmDialog(
            title = "Purge Blocks",
            message = "WARNING: This will permanently delete all objects referencing missing blocks. This is a destructive and irreversible operation.",
            confirmLabel = "Purge",
            destructive = true,
            typeToConfirm = "PURGE",
            onConfirm = {
                scope.launch {
                    try {
                        // Collect all block hashes
                        val allHashes = blockErrors.values.flatten().map { it.blockHash }
                        val hashesJson = allHashes.joinToString(",") { "\"$it\"" }
                        ApiClient.post("/blocks/purge", "[$hashesJson]")
                        actionMessage = "Purge completed"
                        loadData()
                    } catch (e: Exception) {
                        error = "Purge failed: ${e.message}"
                    }
                }
                showPurgeDialog = false
            },
            onDismiss = { showPurgeDialog = false },
        )
    }
}
