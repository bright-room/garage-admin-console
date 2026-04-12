package net.brightroom.garage.web.screens.bucket

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.brightroom.garage.shared.model.bucket.BucketListItem
import net.brightroom.garage.web.api.ApiClient
import net.brightroom.garage.web.components.*
import net.brightroom.garage.web.navigation.Screen

@Stable
class BucketListState(private val scope: CoroutineScope) {
    var buckets by mutableStateOf<List<BucketListItem>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var showCreateDialog by mutableStateOf(false)
    var search by mutableStateOf("")

    val filteredBuckets: List<BucketListItem> by derivedStateOf {
        if (search.isBlank()) buckets
        else buckets.filter { b ->
            b.globalAliases.any { it.contains(search, ignoreCase = true) } ||
                b.id.contains(search, ignoreCase = true)
        }
    }

    fun refresh() {
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

    fun createBucket(alias: String) {
        scope.launch {
            try {
                ApiClient.post("/buckets", """{"globalAlias":"$alias"}""")
                showCreateDialog = false
                refresh()
            } catch (e: Exception) {
                error = "Create failed: ${e.message}"
            }
        }
    }
}

@Composable
fun rememberBucketListState(): BucketListState {
    val scope = rememberCoroutineScope()
    return remember { BucketListState(scope) }
}

@Composable
fun BucketListScreen(onNavigate: (Screen) -> Unit) {
    val state = rememberBucketListState()

    LaunchedEffect(Unit) { state.refresh() }

    BucketListContent(
        buckets = state.filteredBuckets,
        totalCount = state.buckets.size,
        error = state.error,
        loading = state.loading,
        search = state.search,
        showCreateDialog = state.showCreateDialog,
        onSearchChange = { state.search = it },
        onRefresh = state::refresh,
        onNavigate = onNavigate,
        onShowCreateDialog = { state.showCreateDialog = true },
        onDismissCreateDialog = { state.showCreateDialog = false },
        onCreateBucket = state::createBucket,
    )
}

@Composable
fun BucketListContent(
    buckets: List<BucketListItem>,
    totalCount: Int,
    error: String?,
    loading: Boolean,
    search: String,
    showCreateDialog: Boolean,
    modifier: Modifier = Modifier,
    onSearchChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onNavigate: (Screen) -> Unit,
    onShowCreateDialog: () -> Unit,
    onDismissCreateDialog: () -> Unit,
    onCreateBucket: (String) -> Unit,
) {
    if (loading && buckets.isEmpty()) {
        LoadingIndicator()
        return
    }

    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Buckets ($totalCount)",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onShowCreateDialog) {
                    Text("Create Bucket")
                }
                OutlinedButton(onClick = onRefresh) {
                    Text("Refresh")
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        SearchField(value = search, onValueChange = onSearchChange, placeholder = "Search buckets...")
        Spacer(Modifier.height(12.dp))
        error?.let { ErrorBanner(it) }

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
            items = buckets,
            columns = columns,
            onRowClick = { b -> onNavigate(Screen.BucketDetail(b.id)) },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }

    if (showCreateDialog) {
        CreateBucketDialog(
            onDismiss = onDismissCreateDialog,
            onCreate = onCreateBucket,
        )
    }
}
