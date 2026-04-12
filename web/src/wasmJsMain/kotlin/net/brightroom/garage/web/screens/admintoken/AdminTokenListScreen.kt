package net.brightroom.garage.web.screens.admintoken

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
import net.brightroom.garage.shared.model.admintoken.AdminTokenInfo
import net.brightroom.garage.shared.model.admintoken.CreateAdminTokenResponse
import net.brightroom.garage.web.api.ApiClient
import net.brightroom.garage.web.components.*

@Stable
class AdminTokenListState(private val scope: CoroutineScope) {
    var tokens by mutableStateOf<List<AdminTokenInfo>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var createdToken by mutableStateOf<CreateAdminTokenResponse?>(null)
        private set
    var showCreateDialog by mutableStateOf(false)
    var deleteTarget by mutableStateOf<AdminTokenInfo?>(null)

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            try {
                tokens = ApiClient.json.decodeFromString<List<AdminTokenInfo>>(ApiClient.get("/admin-tokens"))
            } catch (e: Exception) {
                error = e.message ?: "Failed to load tokens"
            }
            loading = false
        }
    }

    fun createToken(name: String, scopeList: List<String>) {
        scope.launch {
            try {
                val scopeJson = scopeList.joinToString(",") { "\"$it\"" }
                val result = ApiClient.post("/admin-tokens", """{"name":"$name","scope":[$scopeJson]}""")
                createdToken = ApiClient.json.decodeFromString<CreateAdminTokenResponse>(result)
                showCreateDialog = false
                refresh()
            } catch (e: Exception) {
                error = "Create failed: ${e.message}"
            }
        }
    }

    fun deleteToken(token: AdminTokenInfo) {
        scope.launch {
            try {
                ApiClient.delete("/admin-tokens/${token.id}")
                refresh()
            } catch (e: Exception) {
                error = "Delete failed: ${e.message}"
            }
        }
        deleteTarget = null
    }

    fun dismissCreatedToken() {
        createdToken = null
    }
}

@Composable
fun rememberAdminTokenListState(): AdminTokenListState {
    val scope = rememberCoroutineScope()
    return remember { AdminTokenListState(scope) }
}

@Composable
fun AdminTokenListScreen() {
    val state = rememberAdminTokenListState()

    LaunchedEffect(Unit) { state.refresh() }

    AdminTokenListContent(
        tokens = state.tokens,
        error = state.error,
        loading = state.loading,
        createdToken = state.createdToken,
        showCreateDialog = state.showCreateDialog,
        deleteTarget = state.deleteTarget,
        onRefresh = state::refresh,
        onShowCreateDialog = { state.showCreateDialog = true },
        onDismissCreateDialog = { state.showCreateDialog = false },
        onCreateToken = state::createToken,
        onDeleteTarget = { state.deleteTarget = it },
        onDismissDeleteDialog = { state.deleteTarget = null },
        onDeleteToken = state::deleteToken,
        onDismissCreatedToken = state::dismissCreatedToken,
    )
}

@Composable
fun AdminTokenListContent(
    tokens: List<AdminTokenInfo>,
    error: String?,
    loading: Boolean,
    createdToken: CreateAdminTokenResponse?,
    showCreateDialog: Boolean,
    deleteTarget: AdminTokenInfo?,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit,
    onShowCreateDialog: () -> Unit,
    onDismissCreateDialog: () -> Unit,
    onCreateToken: (name: String, scope: List<String>) -> Unit,
    onDeleteTarget: (AdminTokenInfo) -> Unit,
    onDismissDeleteDialog: () -> Unit,
    onDeleteToken: (AdminTokenInfo) -> Unit,
    onDismissCreatedToken: () -> Unit,
) {
    if (loading && tokens.isEmpty()) {
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
            Text("Admin Tokens (${tokens.size})", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onShowCreateDialog) { Text("Create Token") }
                OutlinedButton(onClick = onRefresh) { Text("Refresh") }
            }
        }

        Spacer(Modifier.height(16.dp))
        error?.let { ErrorBanner(it) }

        // Show created token secret (only shown once)
        createdToken?.let { ct ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Token created! Copy the secret now - it won't be shown again.", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ct.secretToken, fontFamily = FontFamily.Monospace)
                        CopyButton(ct.secretToken)
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onDismissCreatedToken) { Text("Dismiss") }
                }
            }
        }

        tokens.forEach { token ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(token.name, fontWeight = FontWeight.Medium)
                        Text(
                            "ID: ${token.id ?: "N/A"}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            "Scope: ${token.scope.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusChip(if (token.expired) "unavailable" else "healthy")
                        TextButton(
                            onClick = { onDeleteTarget(token) },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateAdminTokenDialog(
            onDismiss = onDismissCreateDialog,
            onCreate = onCreateToken,
        )
    }

    deleteTarget?.let { token ->
        ConfirmDialog(
            title = "Delete Admin Token",
            message = "Delete token \"${token.name}\"? This will revoke access immediately.",
            confirmLabel = "Delete",
            destructive = true,
            typeToConfirm = token.name,
            onConfirm = { onDeleteToken(token) },
            onDismiss = onDismissDeleteDialog,
        )
    }
}
