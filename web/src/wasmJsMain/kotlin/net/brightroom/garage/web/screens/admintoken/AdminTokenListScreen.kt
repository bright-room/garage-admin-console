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
import kotlinx.coroutines.launch
import net.brightroom.garage.shared.model.admintoken.AdminTokenInfo
import net.brightroom.garage.shared.model.admintoken.CreateAdminTokenResponse
import net.brightroom.garage.web.api.ApiClient
import net.brightroom.garage.web.components.*

@Composable
fun AdminTokenListScreen() {
    var tokens by remember { mutableStateOf<List<AdminTokenInfo>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var createdToken by remember { mutableStateOf<CreateAdminTokenResponse?>(null) }
    var deleteTarget by remember { mutableStateOf<AdminTokenInfo?>(null) }
    val scope = rememberCoroutineScope()

    fun loadData() {
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

    LaunchedEffect(Unit) { loadData() }

    if (loading && tokens.isEmpty()) {
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
            Text("Admin Tokens (${tokens.size})", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showCreateDialog = true }) { Text("Create Token") }
                OutlinedButton(onClick = { loadData() }) { Text("Refresh") }
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
                    TextButton(onClick = { createdToken = null }) { Text("Dismiss") }
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
                            onClick = { deleteTarget = token },
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
            onDismiss = { showCreateDialog = false },
            onCreate = { name, scope_list ->
                scope.launch {
                    try {
                        val scopeJson = scope_list.joinToString(",") { "\"$it\"" }
                        val result = ApiClient.post("/admin-tokens", """{"name":"$name","scope":[$scopeJson]}""")
                        createdToken = ApiClient.json.decodeFromString<CreateAdminTokenResponse>(result)
                        showCreateDialog = false
                        loadData()
                    } catch (e: Exception) {
                        error = "Create failed: ${e.message}"
                    }
                }
            },
        )
    }

    deleteTarget?.let { token ->
        ConfirmDialog(
            title = "Delete Admin Token",
            message = "Delete token \"${token.name}\"? This will revoke access immediately.",
            confirmLabel = "Delete",
            destructive = true,
            typeToConfirm = token.name,
            onConfirm = {
                scope.launch {
                    try {
                        ApiClient.delete("/admin-tokens/${token.id}")
                        loadData()
                    } catch (e: Exception) {
                        error = "Delete failed: ${e.message}"
                    }
                }
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}
