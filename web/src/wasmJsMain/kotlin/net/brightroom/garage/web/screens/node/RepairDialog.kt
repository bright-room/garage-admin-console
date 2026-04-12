package net.brightroom.garage.web.screens.node

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val repairTypes = listOf(
    "tables", "blocks", "versions", "multipartUploads",
    "blockRefs", "blockRc", "rebalance", "aliases", "clearResyncQueue",
)

@Composable
fun RepairDialog(onDismiss: () -> Unit, onRepair: (String) -> Unit) {
    var selected by remember { mutableStateOf("tables") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Launch Repair Operation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Select repair type:", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                repairTypes.forEach { type ->
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RadioButton(selected = selected == type, onClick = { selected = type })
                        Text(type, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onRepair(selected) }) { Text("Launch") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
