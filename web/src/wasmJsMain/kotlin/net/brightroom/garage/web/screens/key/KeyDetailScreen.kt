package net.brightroom.garage.web.screens.key

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
import net.brightroom.garage.shared.model.key.KeyInfo
import net.brightroom.garage.web.api.ApiClient
import net.brightroom.garage.web.components.*
import net.brightroom.garage.web.navigation.Screen

@Stable
class KeyDetailState(
    private val keyId: String,
    private val scope: CoroutineScope,
) {
    var keyInfo by mutableStateOf<KeyInfo?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var showSecret by mutableStateOf(false)
    var showDeleteDialog by mutableStateOf(false)

    fun refresh(withSecret: Boolean = false) {
        scope.launch {
            loading = true
            error = null
            try {
                val showSecretParam = if (withSecret) "true" else "false"
                keyInfo = ApiClient.json.decodeFromString<KeyInfo>(
                    ApiClient.get("/keys/$keyId?showSecretKey=$showSecretParam")
                )
            } catch (e: Exception) {
                error = e.message ?: "Failed to load key"
            }
            loading = false
        }
    }

    fun revealSecret() {
        showSecret = true
        refresh(withSecret = true)
    }

    fun deleteKey(onNavigate: (Screen) -> Unit) {
        scope.launch {
            try {
                ApiClient.delete("/keys/$keyId")
                onNavigate(Screen.Keys)
            } catch (e: Exception) {
                error = "Delete failed: ${e.message}"
            }
        }
        showDeleteDialog = false
    }
}

@Composable
fun rememberKeyDetailState(keyId: String): KeyDetailState {
    val scope = rememberCoroutineScope()
    return remember(keyId) { KeyDetailState(keyId, scope) }
}

@Composable
fun KeyDetailScreen(keyId: String, onNavigate: (Screen) -> Unit) {
    val state = rememberKeyDetailState(keyId)

    LaunchedEffect(keyId) { state.refresh() }

    KeyDetailContent(
        keyId = keyId,
        keyInfo = state.keyInfo,
        error = state.error,
        loading = state.loading,
        showSecret = state.showSecret,
        showDeleteDialog = state.showDeleteDialog,
        onNavigate = onNavigate,
        onRevealSecret = state::revealSecret,
        onShowDeleteDialog = { state.showDeleteDialog = true },
        onDismissDeleteDialog = { state.showDeleteDialog = false },
        onDeleteKey = { state.deleteKey(onNavigate) },
    )
}

@Composable
fun KeyDetailContent(
    keyId: String,
    keyInfo: KeyInfo?,
    error: String?,
    loading: Boolean,
    showSecret: Boolean,
    showDeleteDialog: Boolean,
    modifier: Modifier = Modifier,
    onNavigate: (Screen) -> Unit,
    onRevealSecret: () -> Unit,
    onShowDeleteDialog: () -> Unit,
    onDismissDeleteDialog: () -> Unit,
    onDeleteKey: () -> Unit,
) {
    if (loading && keyInfo == null) {
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(onClick = { onNavigate(Screen.Keys) }) {
                    Text("< Keys")
                }
                Text(
                    keyInfo?.name ?: keyId,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            OutlinedButton(
                onClick = onShowDeleteDialog,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Delete")
            }
        }

        Spacer(Modifier.height(16.dp))
        error?.let { ErrorBanner(it) }

        keyInfo?.let { k ->
            // Credentials
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Credentials", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Access Key ID: ", style = MaterialTheme.typography.bodyMedium)
                        Text(k.accessKeyId, fontFamily = FontFamily.Monospace)
                        CopyButton(k.accessKeyId)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Secret Key: ", style = MaterialTheme.typography.bodyMedium)
                        if (showSecret && k.secretAccessKey != null) {
                            Text(k.secretAccessKey!!, fontFamily = FontFamily.Monospace)
                            CopyButton(k.secretAccessKey!!)
                        } else {
                            Text("\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022", fontFamily = FontFamily.Monospace)
                            TextButton(onClick = onRevealSecret) {
                                Text("Show")
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Status: ${if (k.expired) "Expired" else "Active"}")
                        Text("Created: ${k.created?.take(19) ?: "N/A"}")
                        Text("Expiration: ${k.expiration?.take(19) ?: "Never"}")
                    }
                    Text("Can create buckets: ${k.permissions.createBucket}")
                }
            }

            Spacer(Modifier.height(16.dp))

            // Bucket access
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Bucket Access (${k.buckets.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))

                    if (k.buckets.isEmpty()) {
                        Text("No bucket access", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        k.buckets.forEach { bucket ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column {
                                    Text(
                                        bucket.globalAliases.firstOrNull() ?: bucket.id.take(12),
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        bucket.id.take(12),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    PermLabel("R", bucket.permissions.read)
                                    PermLabel("W", bucket.permissions.write)
                                    PermLabel("O", bucket.permissions.owner)
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        ConfirmDialog(
            title = "Delete Access Key",
            message = "This will permanently delete the access key. Associated buckets will NOT be deleted.",
            confirmLabel = "Delete",
            destructive = true,
            typeToConfirm = keyInfo?.name ?: keyId,
            onConfirm = onDeleteKey,
            onDismiss = onDismissDeleteDialog,
        )
    }
}

@Composable
private fun PermLabel(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Text(
        label,
        modifier = modifier,
        fontWeight = if (enabled) FontWeight.Bold else FontWeight.Normal,
        color = if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
    )
}
