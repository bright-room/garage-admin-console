package net.brightroom.garage.web.screens.bucket

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

@Composable
fun CreateBucketDialog(
    onDismiss: () -> Unit,
    onCreate: (globalAlias: String) -> Unit,
) {
    var alias by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Bucket") },
        text = {
            OutlinedTextField(
                value = alias,
                onValueChange = { alias = it },
                label = { Text("Global Alias") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { onCreate(alias) },
                enabled = alias.isNotBlank(),
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
