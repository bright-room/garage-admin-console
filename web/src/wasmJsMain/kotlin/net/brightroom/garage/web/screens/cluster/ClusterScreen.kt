package net.brightroom.garage.web.screens.cluster

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
import net.brightroom.garage.shared.model.cluster.ClusterStatus
import net.brightroom.garage.shared.model.cluster.NodeResp
import net.brightroom.garage.web.api.ApiClient
import net.brightroom.garage.web.components.*

@Stable
class ClusterState(private val scope: CoroutineScope) {
    var status by mutableStateOf<ClusterStatus?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var showConnectDialog by mutableStateOf(false)

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            try {
                status = ApiClient.json.decodeFromString<ClusterStatus>(ApiClient.get("/cluster/status"))
            } catch (e: Exception) {
                error = e.message ?: "Failed to load cluster status"
            }
            loading = false
        }
    }

    fun connectNode(address: String) {
        scope.launch {
            try {
                ApiClient.post("/cluster/connect", "[\"$address\"]")
                showConnectDialog = false
                refresh()
            } catch (e: Exception) {
                error = e.message
            }
        }
    }
}

@Composable
fun rememberClusterState(): ClusterState {
    val scope = rememberCoroutineScope()
    return remember { ClusterState(scope) }
}

@Composable
fun ClusterScreen() {
    val state = rememberClusterState()

    LaunchedEffect(Unit) { state.refresh() }

    ClusterContent(
        status = state.status,
        error = state.error,
        loading = state.loading,
        showConnectDialog = state.showConnectDialog,
        onRefresh = state::refresh,
        onShowConnectDialog = { state.showConnectDialog = true },
        onDismissConnectDialog = { state.showConnectDialog = false },
        onConnectNode = state::connectNode,
    )
}

@Composable
fun ClusterContent(
    status: ClusterStatus?,
    error: String?,
    loading: Boolean,
    showConnectDialog: Boolean,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit,
    onShowConnectDialog: () -> Unit,
    onDismissConnectDialog: () -> Unit,
    onConnectNode: (String) -> Unit,
) {
    if (loading && status == null) {
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
            Text(
                "Cluster Nodes",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onShowConnectDialog) {
                    Text("Connect Node")
                }
                OutlinedButton(onClick = onRefresh) {
                    Text("Refresh")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        status?.let {
            Text(
                "Layout version: ${it.layoutVersion}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))
        error?.let { ErrorBanner(it) }

        status?.let { s ->
            val columns = listOf(
                Column<NodeResp>("ID", width = 120.dp) { node ->
                    Text(
                        node.id.take(12),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                },
                Column<NodeResp>("Hostname") { node ->
                    Text(node.hostname ?: "-", style = MaterialTheme.typography.bodyMedium)
                },
                Column<NodeResp>("Address") { node ->
                    Text(node.addr ?: "-", style = MaterialTheme.typography.bodySmall)
                },
                Column<NodeResp>("Version", width = 100.dp) { node ->
                    Text(node.garageVersion ?: "-", style = MaterialTheme.typography.bodySmall)
                },
                Column<NodeResp>("Zone", width = 80.dp) { node ->
                    Text(node.role?.zone ?: "-", style = MaterialTheme.typography.bodySmall)
                },
                Column<NodeResp>("Capacity", width = 100.dp) { node ->
                    val cap = node.role?.capacity
                    if (cap != null) SizeText(cap) else Text("-")
                },
                Column<NodeResp>("Data Disk") { node ->
                    node.dataPartition?.let { disk ->
                        DiskUsageBar(disk.available, disk.total)
                    } ?: Text("-")
                },
                Column<NodeResp>("Status", width = 100.dp) { node ->
                    StatusChip(
                        when {
                            node.isUp && node.draining -> "degraded"
                            node.isUp -> "healthy"
                            else -> "unavailable"
                        }
                    )
                },
            )

            DataTable(
                items = s.nodes,
                columns = columns,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showConnectDialog) {
        ConnectNodeDialog(
            onDismiss = onDismissConnectDialog,
            onConnect = onConnectNode,
        )
    }
}

@Composable
private fun DiskUsageBar(
    available: Long,
    total: Long,
    modifier: Modifier = Modifier,
) {
    val usedRatio = if (total > 0) 1.0f - (available.toFloat() / total.toFloat()) else 0f
    val usedText = formatSize(total - available)
    val totalText = formatSize(total)

    Column(modifier = modifier) {
        LinearProgressIndicator(
            progress = { usedRatio },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = when {
                usedRatio > 0.9f -> MaterialTheme.colorScheme.error
                usedRatio > 0.7f -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.primary
            },
        )
        Text(
            "$usedText / $totalText",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
