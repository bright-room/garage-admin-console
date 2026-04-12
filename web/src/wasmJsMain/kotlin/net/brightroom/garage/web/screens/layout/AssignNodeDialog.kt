package net.brightroom.garage.web.screens.layout

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AssignNodeDialog(
    onDismiss: () -> Unit,
    onAssign: (nodeId: String, zone: String, capacity: Long, tags: List<String>) -> Unit,
) {
    var nodeId by remember { mutableStateOf("") }
    var zone by remember { mutableStateOf("dc1") }
    var capacityGb by remember { mutableStateOf("20") }
    var tagsStr by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assign Node Role") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = nodeId,
                    onValueChange = { nodeId = it },
                    label = { Text("Node ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = zone,
                    onValueChange = { zone = it },
                    label = { Text("Zone") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = capacityGb,
                    onValueChange = { capacityGb = it },
                    label = { Text("Capacity (GB)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = tagsStr,
                    onValueChange = { tagsStr = it },
                    label = { Text("Tags (comma-separated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val capacityBytes = (capacityGb.toLongOrNull() ?: 20) * 1073741824L
                    val tags = tagsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    onAssign(nodeId, zone, capacityBytes, tags)
                },
                enabled = nodeId.isNotBlank() && zone.isNotBlank(),
            ) {
                Text("Assign")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
