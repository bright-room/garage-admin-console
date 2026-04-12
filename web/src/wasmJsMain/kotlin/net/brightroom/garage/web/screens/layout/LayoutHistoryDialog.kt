package net.brightroom.garage.web.screens.layout

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.brightroom.garage.shared.model.layout.LayoutHistory
import net.brightroom.garage.web.api.ApiClient
import net.brightroom.garage.web.components.LoadingIndicator

@Composable
fun LayoutHistoryDialog(onDismiss: () -> Unit) {
    var history by remember { mutableStateOf<LayoutHistory?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch {
            try {
                history = ApiClient.json.decodeFromString<LayoutHistory>(ApiClient.get("/layout/history"))
            } catch (_: Exception) {}
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Layout History") },
        text = {
            if (loading) {
                Box(Modifier.height(100.dp)) { LoadingIndicator() }
            } else {
                history?.let { h ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Current version: ${h.currentVersion}", fontWeight = FontWeight.Bold)
                        Text("Min acknowledged: ${h.minAck}")
                        Spacer(Modifier.height(4.dp))

                        h.versions.forEach { v ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text("v${v.version}", fontWeight = FontWeight.Bold)
                                    Text(v.status)
                                    Text("${v.storageNodes} storage, ${v.gatewayNodes} gateway")
                                }
                            }
                        }
                    }
                } ?: Text("Failed to load history")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
