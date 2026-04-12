package net.brightroom.garage.web.screens.s3

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.brightroom.garage.shared.model.bucket.BucketListItem
import net.brightroom.garage.web.api.ApiClient
import net.brightroom.garage.web.components.LoadingIndicator
import net.brightroom.garage.web.navigation.Screen

@Composable
fun S3BrowserScreen(onNavigate: (Screen) -> Unit) {
    var buckets by remember { mutableStateOf<List<BucketListItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch {
            try {
                buckets = ApiClient.json.decodeFromString<List<BucketListItem>>(ApiClient.get("/buckets"))
            } catch (_: Exception) {}
            loading = false
        }
    }

    if (loading) {
        LoadingIndicator()
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            "S3 Object Browser",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Select a bucket to browse its objects",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        buckets.forEach { bucket ->
            val alias = bucket.globalAliases.firstOrNull() ?: bucket.id.take(12)
            Card(
                onClick = { onNavigate(Screen.ObjectBrowser(bucket.id, alias)) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(alias, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Text(
                            bucket.id.take(16),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("Browse →", color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        if (buckets.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("No buckets found", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
