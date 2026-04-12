package net.brightroom.garage.web.screens.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.brightroom.garage.shared.model.cluster.ClusterHealth
import net.brightroom.garage.shared.model.cluster.ClusterStatus
import net.brightroom.garage.web.api.ApiClient
import net.brightroom.garage.web.components.ErrorBanner
import net.brightroom.garage.web.components.LoadingIndicator
import net.brightroom.garage.web.components.StatusChip

@Composable
fun DashboardScreen() {
    var health by remember { mutableStateOf<ClusterHealth?>(null) }
    var status by remember { mutableStateOf<ClusterStatus?>(null) }
    var bucketCount by remember { mutableStateOf<Int?>(null) }
    var keyCount by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun loadData() {
        scope.launch {
            loading = true
            error = null
            try {
                health = ApiClient.json.decodeFromString<ClusterHealth>(ApiClient.get("/cluster/health"))
                status = ApiClient.json.decodeFromString<ClusterStatus>(ApiClient.get("/cluster/status"))
                val buckets = ApiClient.json.decodeFromString<List<kotlinx.serialization.json.JsonElement>>(ApiClient.get("/buckets"))
                bucketCount = buckets.size
                val keys = ApiClient.json.decodeFromString<List<kotlinx.serialization.json.JsonElement>>(ApiClient.get("/keys"))
                keyCount = keys.size
            } catch (e: Exception) {
                error = e.message ?: "Failed to load dashboard data"
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { loadData() }

    if (loading && health == null) {
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
            Text(
                "Dashboard",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            IconButton(onClick = { loadData() }) {
                Text("Refresh")
            }
        }

        Spacer(Modifier.height(16.dp))

        error?.let { ErrorBanner(it) }

        health?.let { h ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                StatCard("Cluster Status", modifier = Modifier.weight(1f)) {
                    StatusChip(h.status)
                }
                StatCard("Connected Nodes", modifier = Modifier.weight(1f)) {
                    Text(
                        "${h.connectedNodes} / ${h.knownNodes}",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                StatCard("Storage Nodes", modifier = Modifier.weight(1f)) {
                    Text(
                        "${h.storageNodesUp} / ${h.storageNodes}",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                StatCard("Partitions (Quorum)", modifier = Modifier.weight(1f)) {
                    Text(
                        "${h.partitionsQuorum} / ${h.partitions}",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                StatCard("Partitions (All OK)", modifier = Modifier.weight(1f)) {
                    Text(
                        "${h.partitionsAllOk} / ${h.partitions}",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                StatCard("Buckets", modifier = Modifier.weight(1f)) {
                    Text(
                        "${bucketCount ?: "?"}",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                StatCard("Keys", modifier = Modifier.weight(1f)) {
                    Text(
                        "${keyCount ?: "?"}",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        status?.let { s ->
            Text(
                "Nodes",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Layout version: ${s.layoutVersion}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            s.nodes.forEach { node ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                node.hostname ?: node.id.take(12),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                node.id.take(12),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            node.addr?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                            node.garageVersion?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                            StatusChip(if (node.isUp) "healthy" else "unavailable")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
