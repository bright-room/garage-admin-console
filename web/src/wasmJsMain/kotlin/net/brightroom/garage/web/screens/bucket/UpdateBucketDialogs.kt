package net.brightroom.garage.web.screens.bucket

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun UpdateQuotaDialog(
    currentMaxSize: Long?,
    currentMaxObjects: Long?,
    onDismiss: () -> Unit,
    onUpdate: (maxSize: Long?, maxObjects: Long?) -> Unit,
) {
    var maxSizeMb by remember { mutableStateOf(currentMaxSize?.let { (it / 1048576).toString() } ?: "") }
    var maxObjects by remember { mutableStateOf(currentMaxObjects?.toString() ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Quotas") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = maxSizeMb,
                    onValueChange = { maxSizeMb = it },
                    label = { Text("Max Size (MB, empty = unlimited)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = maxObjects,
                    onValueChange = { maxObjects = it },
                    label = { Text("Max Objects (empty = unlimited)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val sizeBytes = maxSizeMb.toLongOrNull()?.let { it * 1048576 }
                val objCount = maxObjects.toLongOrNull()
                onUpdate(sizeBytes, objCount)
            }) {
                Text("Update")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
fun UpdateWebsiteDialog(
    enabled: Boolean,
    indexDoc: String,
    errorDoc: String?,
    onDismiss: () -> Unit,
    onUpdate: (enabled: Boolean, indexDoc: String, errorDoc: String?) -> Unit,
) {
    var isEnabled by remember { mutableStateOf(enabled) }
    var index by remember { mutableStateOf(indexDoc) }
    var errorDocument by remember { mutableStateOf(errorDoc ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Website Configuration") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Enable website access")
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = isEnabled, onCheckedChange = { isEnabled = it })
                }
                OutlinedTextField(
                    value = index,
                    onValueChange = { index = it },
                    label = { Text("Index Document") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = errorDocument,
                    onValueChange = { errorDocument = it },
                    label = { Text("Error Document (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onUpdate(isEnabled, index, errorDocument.ifBlank { null })
            }) {
                Text("Update")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
