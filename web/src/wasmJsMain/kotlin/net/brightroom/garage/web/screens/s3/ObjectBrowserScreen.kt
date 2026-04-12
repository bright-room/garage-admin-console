package net.brightroom.garage.web.screens.s3

import androidx.compose.foundation.clickable
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
import net.brightroom.garage.shared.model.s3.S3ObjectList
import net.brightroom.garage.web.api.ApiClient
import net.brightroom.garage.web.components.*
import net.brightroom.garage.web.navigation.Screen

@Stable
class ObjectBrowserState(
    private val bucketId: String,
    private val scope: CoroutineScope,
) {
    var prefix by mutableStateOf("")
    var objectList by mutableStateOf<S3ObjectList?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var showUploadDialog by mutableStateOf(false)
    var deleteTarget by mutableStateOf<String?>(null)

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            try {
                val encodedPrefix = prefix
                objectList = ApiClient.json.decodeFromString<S3ObjectList>(
                    ApiClient.get("/s3/$bucketId/objects?prefix=$encodedPrefix")
                )
            } catch (e: Exception) {
                error = e.message ?: "Failed to load objects"
            }
            loading = false
        }
    }

    fun deleteObject(key: String) {
        scope.launch {
            try {
                ApiClient.delete("/s3/$bucketId/objects?key=$key")
                refresh()
            } catch (e: Exception) {
                error = "Delete failed: ${e.message}"
            }
        }
        deleteTarget = null
    }

    fun onUploadComplete() {
        showUploadDialog = false
        refresh()
    }
}

@Composable
fun rememberObjectBrowserState(bucketId: String): ObjectBrowserState {
    val scope = rememberCoroutineScope()
    return remember(bucketId) { ObjectBrowserState(bucketId, scope) }
}

@Composable
fun ObjectBrowserScreen(bucketId: String, bucketAlias: String, onNavigate: (Screen) -> Unit) {
    val state = rememberObjectBrowserState(bucketId)

    LaunchedEffect(state.prefix) { state.refresh() }

    ObjectBrowserContent(
        bucketId = bucketId,
        bucketAlias = bucketAlias,
        prefix = state.prefix,
        objectList = state.objectList,
        error = state.error,
        loading = state.loading,
        showUploadDialog = state.showUploadDialog,
        deleteTarget = state.deleteTarget,
        onPrefixChange = { state.prefix = it },
        onRefresh = state::refresh,
        onNavigate = onNavigate,
        onShowUploadDialog = { state.showUploadDialog = true },
        onDismissUploadDialog = { state.showUploadDialog = false },
        onUploadComplete = state::onUploadComplete,
        onDeleteTarget = { state.deleteTarget = it },
        onDismissDeleteDialog = { state.deleteTarget = null },
        onDeleteObject = state::deleteObject,
    )
}

@Composable
fun ObjectBrowserContent(
    bucketId: String,
    bucketAlias: String,
    prefix: String,
    objectList: S3ObjectList?,
    error: String?,
    loading: Boolean,
    showUploadDialog: Boolean,
    deleteTarget: String?,
    modifier: Modifier = Modifier,
    onPrefixChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onNavigate: (Screen) -> Unit,
    onShowUploadDialog: () -> Unit,
    onDismissUploadDialog: () -> Unit,
    onUploadComplete: () -> Unit,
    onDeleteTarget: (String) -> Unit,
    onDismissDeleteDialog: () -> Unit,
    onDeleteObject: (String) -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
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
                TextButton(onClick = { onNavigate(Screen.BucketDetail(bucketId)) }) {
                    Text("< Bucket")
                }
                Text(
                    bucketAlias.ifBlank { bucketId.take(12) },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onShowUploadDialog) {
                    Text("Upload")
                }
                OutlinedButton(onClick = onRefresh) {
                    Text("Refresh")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Breadcrumb
        Breadcrumb(prefix = prefix, onNavigate = onPrefixChange)

        Spacer(Modifier.height(12.dp))
        error?.let { ErrorBanner(it) }

        if (loading && objectList == null) {
            LoadingIndicator()
            return
        }

        // Object list
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            // Prefix folders
            objectList?.commonPrefixes?.forEach { cp ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onPrefixChange(cp) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("\uD83D\uDCC1", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            cp.removePrefix(prefix),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            }

            // Objects
            objectList?.objects?.forEach { obj ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("\uD83D\uDCC4", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            obj.key.removePrefix(prefix),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SizeText(obj.size)
                        Text(
                            obj.lastModified.take(19),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = {
                            kotlinx.browser.window.open(
                                "/api/s3/$bucketId/download?key=${obj.key}", "_blank"
                            )
                        }) {
                            Text("Download")
                        }
                        TextButton(
                            onClick = { onDeleteTarget(obj.key) },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text("Delete")
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            }

            if (objectList?.objects.isNullOrEmpty() && objectList?.commonPrefixes.isNullOrEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No objects in this prefix", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (showUploadDialog) {
        UploadDialog(
            currentPrefix = prefix,
            onDismiss = onDismissUploadDialog,
            onUpload = { onUploadComplete() },
            bucketId = bucketId,
        )
    }

    deleteTarget?.let { key ->
        ConfirmDialog(
            title = "Delete Object",
            message = "Delete \"$key\"?",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { onDeleteObject(key) },
            onDismiss = onDismissDeleteDialog,
        )
    }
}

@Composable
private fun Breadcrumb(
    prefix: String,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = { onNavigate("") }) {
            Text("/", fontWeight = FontWeight.Bold)
        }

        if (prefix.isNotEmpty()) {
            val parts = prefix.trimEnd('/').split("/")
            parts.forEachIndexed { index, part ->
                Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
                val targetPrefix = parts.take(index + 1).joinToString("/") + "/"
                TextButton(onClick = { onNavigate(targetPrefix) }) {
                    Text(part)
                }
            }
        }
    }
}
