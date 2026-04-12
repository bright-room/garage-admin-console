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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import net.brightroom.garage.shared.model.block.BlockError
import net.brightroom.garage.web.api.ApiClient
import net.brightroom.garage.web.components.ConfirmDialog
import net.brightroom.garage.web.components.ErrorBanner
import net.brightroom.garage.web.components.LoadingIndicator

@Stable
class BlockErrorState(private val scope: CoroutineScope) {
    var blockErrors by mutableStateOf<Map<String, List<BlockError>>>(emptyMap())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var actionMessage by mutableStateOf<String?>(null)
        private set
    var showPurgeDialog by mutableStateOf(false)

    val totalErrors: Int by derivedStateOf {
        blockErrors.values.sumOf { it.size }
    }

    fun refresh() {
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

    fun retryAll() {
        scope.launch {
            try {
                ApiClient.post("/blocks/retry-resync", """{"all":true}""")
                actionMessage = "Retry resync for all blocks queued"
                refresh()
            } catch (e: Exception) {
                error = "Retry failed: ${e.message}"
            }
        }
    }

    fun purge() {
        scope.launch {
            try {
                val allHashes = blockErrors.values.flatten().map { it.blockHash }
                val hashesJson = allHashes.joinToString(",") { "\"$it\"" }
                ApiClient.post("/blocks/purge", "[$hashesJson]")
                actionMessage = "Purge completed"
                refresh()
            } catch (e: Exception) {
                error = "Purge failed: ${e.message}"
            }
        }
        showPurgeDialog = false
    }

    fun dismissActionMessage() {
        actionMessage = null
    }
}

@Composable
fun rememberBlockErrorState(): BlockErrorState {
    val scope = rememberCoroutineScope()
    return remember { BlockErrorState(scope) }
}

@Composable
fun BlockErrorScreen() {
    val state = rememberBlockErrorState()

    LaunchedEffect(Unit) { state.refresh() }

    BlockErrorContent(
        blockErrors = state.blockErrors,
        totalErrors = state.totalErrors,
        error = state.error,
        loading = state.loading,
        actionMessage = state.actionMessage,
        showPurgeDialog = state.showPurgeDialog,
        onRefresh = state::refresh,
        onRetryAll = state::retryAll,
        onShowPurgeDialog = { state.showPurgeDialog = true },
        onDismissPurgeDialog = { state.showPurgeDialog = false },
        onPurge = state::purge,
        onDismissActionMessage = state::dismissActionMessage,
    )
}

@Composable
fun BlockErrorContent(
    blockErrors: Map<String, List<BlockError>>,
    totalErrors: Int,
    error: String?,
    loading: Boolean,
    actionMessage: String?,
    showPurgeDialog: Boolean,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit,
    onRetryAll: () -> Unit,
    onShowPurgeDialog: () -> Unit,
    onDismissPurgeDialog: () -> Unit,
    onPurge: () -> Unit,
    onDismissActionMessage: () -> Unit,
) {
    if (loading && blockErrors.isEmpty()) {
        LoadingIndicator()
        return
    }

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
            Text("Block Errors", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRetryAll) { Text("Retry All") }
                OutlinedButton(
                    onClick = onShowPurgeDialog,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Purge") }
                OutlinedButton(onClick = onRefresh) { Text("Refresh") }
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
                    TextButton(onClick = onDismissActionMessage) { Text("Dismiss") }
                }
            }
        }

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
            onConfirm = onPurge,
            onDismiss = onDismissPurgeDialog,
        )
    }
}
