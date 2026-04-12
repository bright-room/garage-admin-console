package net.brightroom.garage.web.screens.key

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.brightroom.garage.shared.model.key.KeyListItem
import net.brightroom.garage.web.api.ApiClient
import net.brightroom.garage.web.components.*
import net.brightroom.garage.web.navigation.Screen

@Composable
fun KeyListScreen(onNavigate: (Screen) -> Unit) {
    var keys by remember { mutableStateOf<List<KeyListItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun loadData() {
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

    LaunchedEffect(Unit) { loadData() }

    if (loading && keys.isEmpty()) {
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
                "Access Keys (${keys.size})",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showCreateDialog = true }) {
                    Text("Create Key")
                }
                OutlinedButton(onClick = { showImportDialog = true }) {
                    Text("Import Key")
                }
                OutlinedButton(onClick = { loadData() }) {
                    Text("Refresh")
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        SearchField(value = search, onValueChange = { search = it }, placeholder = "Search keys...")
        Spacer(Modifier.height(12.dp))
        error?.let { ErrorBanner(it) }

        val filtered = keys.filter { k ->
            search.isBlank() || k.name.contains(search, ignoreCase = true) || k.id.contains(search, ignoreCase = true)
        }

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
            items = filtered,
            columns = columns,
            onRowClick = { k -> onNavigate(Screen.KeyDetail(k.id)) },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }

    if (showCreateDialog) {
        CreateKeyDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                scope.launch {
                    try {
                        ApiClient.post("/keys", """{"name":"$name"}""")
                        showCreateDialog = false
                        loadData()
                    } catch (e: Exception) {
                        error = "Create failed: ${e.message}"
                    }
                }
            },
        )
    }

    if (showImportDialog) {
        ImportKeyDialog(
            onDismiss = { showImportDialog = false },
            onImport = { accessKey, secretKey, name ->
                scope.launch {
                    try {
                        val namePart = if (name.isBlank()) "null" else "\"$name\""
                        ApiClient.post("/keys/import", """{"accessKeyId":"$accessKey","secretAccessKey":"$secretKey","name":$namePart}""")
                        showImportDialog = false
                        loadData()
                    } catch (e: Exception) {
                        error = "Import failed: ${e.message}"
                    }
                }
            },
        )
    }
}
