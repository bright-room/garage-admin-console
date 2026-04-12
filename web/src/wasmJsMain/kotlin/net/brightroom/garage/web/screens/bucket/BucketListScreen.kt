package net.brightroom.garage.web.screens.bucket

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
import net.brightroom.garage.shared.model.bucket.BucketListItem
import net.brightroom.garage.web.api.ApiClient
import net.brightroom.garage.web.components.*
import net.brightroom.garage.web.navigation.Screen

@Composable
fun BucketListScreen(onNavigate: (Screen) -> Unit) {
    var buckets by remember { mutableStateOf<List<BucketListItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun loadData() {
        scope.launch {
            loading = true
            error = null
            try {
                buckets = ApiClient.json.decodeFromString<List<BucketListItem>>(ApiClient.get("/buckets"))
            } catch (e: Exception) {
                error = e.message ?: "Failed to load buckets"
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { loadData() }

    if (loading && buckets.isEmpty()) {
        LoadingIndicator()
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Buckets (${buckets.size})",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showCreateDialog = true }) {
                    Text("Create Bucket")
                }
                OutlinedButton(onClick = { loadData() }) {
                    Text("Refresh")
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        SearchField(value = search, onValueChange = { search = it }, placeholder = "Search buckets...")
        Spacer(Modifier.height(12.dp))
        error?.let { ErrorBanner(it) }

        val filtered = buckets.filter { b ->
            search.isBlank() ||
                b.globalAliases.any { it.contains(search, ignoreCase = true) } ||
                b.id.contains(search, ignoreCase = true)
        }

        val columns = listOf(
            Column<BucketListItem>("ID", width = 120.dp) { b ->
                Text(b.id.take(12), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            },
            Column<BucketListItem>("Global Aliases") { b ->
                Text(
                    b.globalAliases.joinToString(", ").ifEmpty { "-" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            },
            Column<BucketListItem>("Local Aliases") { b ->
                Text(
                    b.localAliases.joinToString(", ") { "${it.alias} (${it.accessKeyId.take(8)})" }.ifEmpty { "-" },
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            Column<BucketListItem>("Created", width = 160.dp) { b ->
                Text(b.created.take(19), style = MaterialTheme.typography.bodySmall)
            },
        )

        DataTable(
            items = filtered,
            columns = columns,
            onRowClick = { b -> onNavigate(Screen.BucketDetail(b.id)) },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }

    if (showCreateDialog) {
        CreateBucketDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { alias ->
                scope.launch {
                    try {
                        ApiClient.post("/buckets", """{"globalAlias":"$alias"}""")
                        showCreateDialog = false
                        loadData()
                    } catch (e: Exception) {
                        error = "Create failed: ${e.message}"
                    }
                }
            },
        )
    }
}
