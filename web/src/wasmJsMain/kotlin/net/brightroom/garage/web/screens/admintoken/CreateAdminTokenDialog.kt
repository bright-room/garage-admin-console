package net.brightroom.garage.web.screens.admintoken

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CreateAdminTokenDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, scope: List<String>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var scopeStr by remember { mutableStateOf("*") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Admin Token") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Token Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = scopeStr,
                    onValueChange = { scopeStr = it },
                    label = { Text("Scope (comma-separated, * for all)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Use * to grant access to all endpoints.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val scopes = scopeStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    onCreate(name, scopes)
                },
                enabled = name.isNotBlank(),
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
