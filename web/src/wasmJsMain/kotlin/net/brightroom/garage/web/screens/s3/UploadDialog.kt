package net.brightroom.garage.web.screens.s3

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun UploadDialog(
    currentPrefix: String,
    bucketId: String,
    onDismiss: () -> Unit,
    onUpload: (key: String) -> Unit,
) {
    var key by remember { mutableStateOf(currentPrefix) }
    var message by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Upload Object") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "To upload files, use the S3 API directly or a tool like aws-cli / s5cmd.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Example using aws-cli:",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "aws s3 cp <file> s3://$bucketId/${currentPrefix}<filename>",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                    }
                }
                message?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
