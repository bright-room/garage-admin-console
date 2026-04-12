package net.brightroom.garage.web.screens.layout

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
import net.brightroom.garage.shared.model.layout.ClusterLayout
import net.brightroom.garage.shared.model.layout.LayoutNodeRole
import net.brightroom.garage.shared.model.layout.StagedRoleChange
import net.brightroom.garage.web.api.ApiClient
import net.brightroom.garage.web.components.*

@Stable
class LayoutState(private val scope: CoroutineScope) {
    var layout by mutableStateOf<ClusterLayout?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var actionMessage by mutableStateOf<String?>(null)
        private set
    var showAssignDialog by mutableStateOf(false)
    var showHistoryDialog by mutableStateOf(false)

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            try {
                layout = ApiClient.json.decodeFromString<ClusterLayout>(ApiClient.get("/layout"))
            } catch (e: Exception) {
                error = e.message ?: "Failed to load layout"
            }
            loading = false
        }
    }

    fun applyChanges() {
        val currentLayout = layout ?: return
        scope.launch {
            try {
                val newVersion = currentLayout.version + 1
                ApiClient.post("/layout/apply", """{"version":$newVersion}""")
                actionMessage = "Layout applied successfully"
                refresh()
            } catch (e: Exception) {
                error = "Apply failed: ${e.message}"
            }
        }
    }

    fun preview() {
        scope.launch {
            try {
                ApiClient.post("/layout/preview")
                actionMessage = "Preview generated (check server logs)"
                refresh()
            } catch (e: Exception) {
                error = "Preview failed: ${e.message}"
            }
        }
    }

    fun revert() {
        scope.launch {
            try {
                ApiClient.post("/layout/revert")
                actionMessage = "Staged changes reverted"
                refresh()
            } catch (e: Exception) {
                error = "Revert failed: ${e.message}"
            }
        }
    }

    fun assignNode(nodeId: String, zone: String, capacity: Long, tags: List<String>) {
        scope.launch {
            try {
                val tagsJson = tags.map { "\"$it\"" }.joinToString(",")
                val body = """{"roles":[{"id":"$nodeId","zone":"$zone","capacity":$capacity,"tags":[$tagsJson]}]}"""
                ApiClient.post("/layout/update", body)
                showAssignDialog = false
                actionMessage = "Node role staged for assignment"
                refresh()
            } catch (e: Exception) {
                error = "Assign failed: ${e.message}"
            }
        }
    }
}

@Composable
fun rememberLayoutState(): LayoutState {
    val scope = rememberCoroutineScope()
    return remember { LayoutState(scope) }
}

@Composable
fun LayoutScreen() {
    val state = rememberLayoutState()

    LaunchedEffect(Unit) { state.refresh() }

    LayoutContent(
        layout = state.layout,
        error = state.error,
        loading = state.loading,
        actionMessage = state.actionMessage,
        showAssignDialog = state.showAssignDialog,
        showHistoryDialog = state.showHistoryDialog,
        onRefresh = state::refresh,
        onApplyChanges = state::applyChanges,
        onPreview = state::preview,
        onRevert = state::revert,
        onShowAssignDialog = { state.showAssignDialog = true },
        onDismissAssignDialog = { state.showAssignDialog = false },
        onAssignNode = state::assignNode,
        onShowHistoryDialog = { state.showHistoryDialog = true },
        onDismissHistoryDialog = { state.showHistoryDialog = false },
    )
}

@Composable
fun LayoutContent(
    layout: ClusterLayout?,
    error: String?,
    loading: Boolean,
    actionMessage: String?,
    showAssignDialog: Boolean,
    showHistoryDialog: Boolean,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit,
    onApplyChanges: () -> Unit,
    onPreview: () -> Unit,
    onRevert: () -> Unit,
    onShowAssignDialog: () -> Unit,
    onDismissAssignDialog: () -> Unit,
    onAssignNode: (nodeId: String, zone: String, capacity: Long, tags: List<String>) -> Unit,
    onShowHistoryDialog: () -> Unit,
    onDismissHistoryDialog: () -> Unit,
) {
    if (loading && layout == null) {
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
                "Cluster Layout",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onShowHistoryDialog) {
                    Text("History")
                }
                OutlinedButton(onClick = onShowAssignDialog) {
                    Text("Assign Node")
                }
                OutlinedButton(onClick = onRefresh) {
                    Text("Refresh")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        layout?.let { l ->
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text("Version: ${l.version}", style = MaterialTheme.typography.bodyMedium)
                Text("Partition size: ${formatSize(l.partitionSize)}", style = MaterialTheme.typography.bodyMedium)
                val zr = l.parameters.zoneRedundancy
                val zrText = if (zr is JsonPrimitive && zr.isString) zr.content
                else zr.toString()
                Text("Zone redundancy: $zrText", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(16.dp))
        error?.let { ErrorBanner(it) }
        actionMessage?.let {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Text(it, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }

        // Current roles
        layout?.let { l ->
            Text(
                "Current Roles",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))

            val roleColumns = listOf(
                Column<LayoutNodeRole>("Node ID", width = 120.dp) { role ->
                    Text(role.id.take(12), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                },
                Column<LayoutNodeRole>("Zone", width = 80.dp) { role ->
                    Text(role.zone, style = MaterialTheme.typography.bodyMedium)
                },
                Column<LayoutNodeRole>("Tags") { role ->
                    Text(role.tags.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                },
                Column<LayoutNodeRole>("Capacity", width = 100.dp) { role ->
                    role.capacity?.let { SizeText(it) } ?: Text("Gateway")
                },
                Column<LayoutNodeRole>("Stored Partitions", width = 120.dp) { role ->
                    Text("${role.storedPartitions ?: "-"}", style = MaterialTheme.typography.bodySmall)
                },
                Column<LayoutNodeRole>("Usable Capacity", width = 120.dp) { role ->
                    role.usableCapacity?.let { SizeText(it) } ?: Text("-")
                },
            )

            DataTable(
                items = l.roles,
                columns = roleColumns,
                modifier = Modifier.fillMaxWidth(),
            )

            // Staged changes
            if (l.stagedRoleChanges.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "Staged Changes (${l.stagedRoleChanges.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.height(8.dp))

                val stagedColumns = listOf(
                    Column<StagedRoleChange>("Node ID", width = 120.dp) { change ->
                        Text(change.id.take(12), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    },
                    Column<StagedRoleChange>("Action", width = 100.dp) { change ->
                        if (change.remove == true) {
                            Text("REMOVE", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        } else {
                            Text("ASSIGN", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    },
                    Column<StagedRoleChange>("Zone", width = 80.dp) { change ->
                        Text(change.zone ?: "-")
                    },
                    Column<StagedRoleChange>("Tags") { change ->
                        Text(change.tags?.joinToString(", ") ?: "-")
                    },
                    Column<StagedRoleChange>("Capacity", width = 100.dp) { change ->
                        change.capacity?.let { SizeText(it) } ?: Text("-")
                    },
                )

                DataTable(
                    items = l.stagedRoleChanges,
                    columns = stagedColumns,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onApplyChanges) {
                        Text("Apply Changes")
                    }
                    OutlinedButton(onClick = onPreview) {
                        Text("Preview")
                    }
                    OutlinedButton(
                        onClick = onRevert,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("Revert")
                    }
                }
            }
        }
    }

    if (showAssignDialog) {
        AssignNodeDialog(
            onDismiss = onDismissAssignDialog,
            onAssign = onAssignNode,
        )
    }

    if (showHistoryDialog) {
        LayoutHistoryDialog(onDismiss = onDismissHistoryDialog)
    }
}
