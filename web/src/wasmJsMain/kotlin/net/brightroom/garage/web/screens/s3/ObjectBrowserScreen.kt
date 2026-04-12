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
import kotlinx.coroutines.launch
import net.brightroom.garage.shared.model.s3.S3ObjectItem
import net.brightroom.garage.shared.model.s3.S3ObjectList
import net.brightroom.garage.web.api.ApiClient
import net.brightroom.garage.web.components.*
import net.brightroom.garage.web.navigation.Screen

@Composable
fun ObjectBrowserScreen(bucketId: String, bucketAlias: String, onNavigate: (Screen) -> Unit) {
    var prefix by remember { mutableStateOf("") }
    var objectList by remember { mutableStateOf<S3ObjectList?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showUploadDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun loadData() {
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

    LaunchedEffect(prefix) { loadData() }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
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
                Button(onClick = { showUploadDialog = true }) {
                    Text("Upload")
                }
                OutlinedButton(onClick = { loadData() }) {
                    Text("Refresh")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Breadcrumb
        Breadcrumb(prefix = prefix, onNavigate = { prefix = it })

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
                        .clickable { prefix = cp }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("📁", style = MaterialTheme.typography.bodyLarge)
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
                        Text("📄", style = MaterialTheme.typography.bodyLarge)
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
                            onClick = { deleteTarget = obj.key },
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
            onDismiss = { showUploadDialog = false },
            onUpload = { key ->
                showUploadDialog = false
                loadData()
            },
            bucketId = bucketId,
        )
    }

    deleteTarget?.let { key ->
        ConfirmDialog(
            title = "Delete Object",
            message = "Delete \"$key\"?",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                scope.launch {
                    try {
                        ApiClient.delete("/s3/$bucketId/objects?key=$key")
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

@Composable
private fun Breadcrumb(prefix: String, onNavigate: (String) -> Unit) {
    Row(
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
