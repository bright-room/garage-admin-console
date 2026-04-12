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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.brightroom.garage.shared.model.bucket.BucketInfo
import net.brightroom.garage.web.api.ApiClient
import net.brightroom.garage.web.components.*
import net.brightroom.garage.web.navigation.Screen

@Stable
class BucketDetailState(
    private val bucketId: String,
    private val scope: CoroutineScope,
) {
    var bucket by mutableStateOf<BucketInfo?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var showDeleteDialog by mutableStateOf(false)
    var showQuotaDialog by mutableStateOf(false)
    var showWebsiteDialog by mutableStateOf(false)

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            try {
                bucket = ApiClient.json.decodeFromString<BucketInfo>(ApiClient.get("/buckets/$bucketId"))
            } catch (e: Exception) {
                error = e.message ?: "Failed to load bucket"
            }
            loading = false
        }
    }

    fun deleteBucket(onNavigate: (Screen) -> Unit) {
        scope.launch {
            try {
                ApiClient.delete("/buckets/$bucketId")
                onNavigate(Screen.Buckets)
            } catch (e: Exception) {
                error = "Delete failed: ${e.message}"
            }
        }
        showDeleteDialog = false
    }

    fun updateQuotas(maxSize: Long?, maxObjects: Long?) {
        scope.launch {
            try {
                val body = buildString {
                    append("""{"quotas":{"maxSize":""")
                    append(maxSize?.toString() ?: "null")
                    append(""","maxObjects":""")
                    append(maxObjects?.toString() ?: "null")
                    append("}}")
                }
                ApiClient.post("/buckets/$bucketId", body)
                showQuotaDialog = false
                refresh()
            } catch (e: Exception) {
                error = "Update failed: ${e.message}"
            }
        }
    }

    fun updateWebsite(enabled: Boolean, indexDoc: String, errorDoc: String?) {
        scope.launch {
            try {
                val errorPart = if (errorDoc.isNullOrBlank()) "null" else "\"$errorDoc\""
                val body = """{"websiteAccess":{"enabled":$enabled,"indexDocument":"$indexDoc","errorDocument":$errorPart}}"""
                ApiClient.post("/buckets/$bucketId", body)
                showWebsiteDialog = false
                refresh()
            } catch (e: Exception) {
                error = "Update failed: ${e.message}"
            }
        }
    }
}

@Composable
fun rememberBucketDetailState(bucketId: String): BucketDetailState {
    val scope = rememberCoroutineScope()
    return remember(bucketId) { BucketDetailState(bucketId, scope) }
}

@Composable
fun BucketDetailScreen(bucketId: String, onNavigate: (Screen) -> Unit) {
    val state = rememberBucketDetailState(bucketId)

    LaunchedEffect(bucketId) { state.refresh() }

    BucketDetailContent(
        bucketId = bucketId,
        bucket = state.bucket,
        error = state.error,
        loading = state.loading,
        showDeleteDialog = state.showDeleteDialog,
        showQuotaDialog = state.showQuotaDialog,
        showWebsiteDialog = state.showWebsiteDialog,
        onNavigate = onNavigate,
        onRefresh = state::refresh,
        onShowDeleteDialog = { state.showDeleteDialog = true },
        onDismissDeleteDialog = { state.showDeleteDialog = false },
        onDeleteBucket = { state.deleteBucket(onNavigate) },
        onShowQuotaDialog = { state.showQuotaDialog = true },
        onDismissQuotaDialog = { state.showQuotaDialog = false },
        onUpdateQuotas = state::updateQuotas,
        onShowWebsiteDialog = { state.showWebsiteDialog = true },
        onDismissWebsiteDialog = { state.showWebsiteDialog = false },
        onUpdateWebsite = state::updateWebsite,
    )
}

@Composable
fun BucketDetailContent(
    bucketId: String,
    bucket: BucketInfo?,
    error: String?,
    loading: Boolean,
    showDeleteDialog: Boolean,
    showQuotaDialog: Boolean,
    showWebsiteDialog: Boolean,
    modifier: Modifier = Modifier,
    onNavigate: (Screen) -> Unit,
    onRefresh: () -> Unit,
    onShowDeleteDialog: () -> Unit,
    onDismissDeleteDialog: () -> Unit,
    onDeleteBucket: () -> Unit,
    onShowQuotaDialog: () -> Unit,
    onDismissQuotaDialog: () -> Unit,
    onUpdateQuotas: (maxSize: Long?, maxObjects: Long?) -> Unit,
    onShowWebsiteDialog: () -> Unit,
    onDismissWebsiteDialog: () -> Unit,
    onUpdateWebsite: (enabled: Boolean, indexDoc: String, errorDoc: String?) -> Unit,
) {
    if (loading && bucket == null) {
        LoadingIndicator()
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(onClick = { onNavigate(Screen.Buckets) }) {
                    Text("< Buckets")
                }
                Text(
                    bucket?.globalAliases?.firstOrNull() ?: bucketId.take(12),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val alias = bucket?.globalAliases?.firstOrNull() ?: ""
                    onNavigate(Screen.ObjectBrowser(bucketId, alias))
                }) {
                    Text("Browse Objects")
                }
                OutlinedButton(
                    onClick = onShowDeleteDialog,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Delete")
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        error?.let { ErrorBanner(it) }

        bucket?.let { b ->
            // Info card
            Text(
                "ID: ${b.id}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Created: ${b.created.take(19)}", style = MaterialTheme.typography.bodySmall)

            Spacer(Modifier.height(16.dp))

            // Stats
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                StatCard("Objects", Modifier.weight(1f)) {
                    Text("${b.objects}", style = MaterialTheme.typography.headlineSmall)
                }
                StatCard("Size", Modifier.weight(1f)) {
                    SizeText(b.bytes)
                }
                StatCard("Unfinished Uploads", Modifier.weight(1f)) {
                    Text("${b.unfinishedUploads}", style = MaterialTheme.typography.headlineSmall)
                }
            }

            Spacer(Modifier.height(16.dp))

            // Aliases
            SectionCard("Global Aliases") {
                if (b.globalAliases.isEmpty()) {
                    Text("No global aliases", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    b.globalAliases.forEach { alias ->
                        Text(alias, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Quotas
            SectionCard("Quotas") {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text("Max size: ${b.quotas.maxSize?.let { formatSize(it) } ?: "Unlimited"}")
                    Text("Max objects: ${b.quotas.maxObjects ?: "Unlimited"}")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onShowQuotaDialog) {
                    Text("Edit Quotas")
                }
            }

            Spacer(Modifier.height(12.dp))

            // Website
            SectionCard("Website Access") {
                Text("Enabled: ${b.websiteAccess}")
                b.websiteConfig?.let { wc ->
                    Text("Index: ${wc.indexDocument}")
                    wc.errorDocument?.let { Text("Error: $it") }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onShowWebsiteDialog) {
                    Text("Configure Website")
                }
            }

            Spacer(Modifier.height(12.dp))

            // Permissions
            SectionCard("Key Permissions (${b.keys.size})") {
                b.keys.forEach { key ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(key.name, fontWeight = FontWeight.Medium)
                            Text(key.accessKeyId.take(20), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PermChip("R", key.permissions.read)
                            PermChip("W", key.permissions.write)
                            PermChip("O", key.permissions.owner)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }
            }
        }
    }

    if (showDeleteDialog) {
        ConfirmDialog(
            title = "Delete Bucket",
            message = "This will permanently delete the bucket. The bucket must be empty.",
            confirmLabel = "Delete",
            destructive = true,
            typeToConfirm = bucket?.globalAliases?.firstOrNull() ?: bucketId.take(12),
            onConfirm = onDeleteBucket,
            onDismiss = onDismissDeleteDialog,
        )
    }

    if (showQuotaDialog) {
        UpdateQuotaDialog(
            currentMaxSize = bucket?.quotas?.maxSize,
            currentMaxObjects = bucket?.quotas?.maxObjects,
            onDismiss = onDismissQuotaDialog,
            onUpdate = onUpdateQuotas,
        )
    }

    if (showWebsiteDialog) {
        UpdateWebsiteDialog(
            enabled = bucket?.websiteAccess ?: false,
            indexDoc = bucket?.websiteConfig?.indexDocument ?: "index.html",
            errorDoc = bucket?.websiteConfig?.errorDocument,
            onDismiss = onDismissWebsiteDialog,
            onUpdate = onUpdateWebsite,
        )
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
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun PermChip(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    SuggestionChip(
        onClick = {},
        label = {
            Text(
                label,
                color = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        },
        modifier = modifier,
    )
}
