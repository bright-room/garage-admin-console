package net.brightroom.garage.web.screens.key

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
import net.brightroom.garage.shared.model.key.KeyListItem
import net.brightroom.garage.web.api.ApiClient
import net.brightroom.garage.web.components.*
import net.brightroom.garage.web.navigation.Screen

@Stable
class KeyListState(private val scope: CoroutineScope) {
    var keys by mutableStateOf<List<KeyListItem>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var showCreateDialog by mutableStateOf(false)
    var showImportDialog by mutableStateOf(false)
    var search by mutableStateOf("")

    val filteredKeys: List<KeyListItem> by derivedStateOf {
        if (search.isBlank()) keys
        else keys.filter { k ->
            k.name.contains(search, ignoreCase = true) || k.id.contains(search, ignoreCase = true)
        }
    }

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            try {
                keys = ApiClient.json.decodeFromString<List<KeyListItem>>(ApiClient.get("/keys"))
            } catch (e: Exception) {
                error = e.message ?: "Failed to load keys"
            }
            loading = false
        }
    }

    fun createKey(name: String) {
        scope.launch {
            try {
                ApiClient.post("/keys", """{"name":"$name"}""")
                showCreateDialog = false
                refresh()
            } catch (e: Exception) {
                error = "Create failed: ${e.message}"
            }
        }
    }

    fun importKey(accessKey: String, secretKey: String, name: String) {
        scope.launch {
            try {
                val namePart = if (name.isBlank()) "null" else "\"$name\""
                ApiClient.post("/keys/import", """{"accessKeyId":"$accessKey","secretAccessKey":"$secretKey","name":$namePart}""")
                showImportDialog = false
                refresh()
            } catch (e: Exception) {
                error = "Import failed: ${e.message}"
            }
        }
    }
}

@Composable
fun rememberKeyListState(): KeyListState {
    val scope = rememberCoroutineScope()
    return remember { KeyListState(scope) }
}

@Composable
fun KeyListScreen(onNavigate: (Screen) -> Unit) {
    val state = rememberKeyListState()

    LaunchedEffect(Unit) { state.refresh() }

    KeyListContent(
        keys = state.filteredKeys,
        totalCount = state.keys.size,
        error = state.error,
        loading = state.loading,
        search = state.search,
        showCreateDialog = state.showCreateDialog,
        showImportDialog = state.showImportDialog,
        onSearchChange = { state.search = it },
        onRefresh = state::refresh,
        onNavigate = onNavigate,
        onShowCreateDialog = { state.showCreateDialog = true },
        onDismissCreateDialog = { state.showCreateDialog = false },
        onCreateKey = state::createKey,
        onShowImportDialog = { state.showImportDialog = true },
        onDismissImportDialog = { state.showImportDialog = false },
        onImportKey = state::importKey,
    )
}

@Composable
fun KeyListContent(
    keys: List<KeyListItem>,
    totalCount: Int,
    error: String?,
    loading: Boolean,
    search: String,
    showCreateDialog: Boolean,
    showImportDialog: Boolean,
    modifier: Modifier = Modifier,
    onSearchChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onNavigate: (Screen) -> Unit,
    onShowCreateDialog: () -> Unit,
    onDismissCreateDialog: () -> Unit,
    onCreateKey: (String) -> Unit,
    onShowImportDialog: () -> Unit,
    onDismissImportDialog: () -> Unit,
    onImportKey: (accessKey: String, secretKey: String, name: String) -> Unit,
) {
    if (loading && keys.isEmpty()) {
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
                "Access Keys ($totalCount)",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onShowCreateDialog) {
                    Text("Create Key")
                }
                OutlinedButton(onClick = onShowImportDialog) {
                    Text("Import Key")
                }
                OutlinedButton(onClick = onRefresh) {
                    Text("Refresh")
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        SearchField(value = search, onValueChange = onSearchChange, placeholder = "Search keys...")
        Spacer(Modifier.height(12.dp))
        error?.let { ErrorBanner(it) }

        val columns = listOf(
            Column<KeyListItem>("Access Key ID", width = 200.dp) { k ->
                Text(k.id, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            },
            Column<KeyListItem>("Name") { k ->
                Text(k.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            },
            Column<KeyListItem>("Created", width = 160.dp) { k ->
                Text(k.created?.take(19) ?: "-", style = MaterialTheme.typography.bodySmall)
            },
            Column<KeyListItem>("Status", width = 100.dp) { k ->
                StatusChip(if (k.expired) "unavailable" else "healthy")
            },
        )

        DataTable(
            items = keys,
            columns = columns,
            onRowClick = { k -> onNavigate(Screen.KeyDetail(k.id)) },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }

    if (showCreateDialog) {
        CreateKeyDialog(
            onDismiss = onDismissCreateDialog,
            onCreate = onCreateKey,
        )
    }

    if (showImportDialog) {
        ImportKeyDialog(
            onDismiss = onDismissImportDialog,
            onImport = onImportKey,
        )
    }
}
