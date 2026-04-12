package net.brightroom.garage.web.screens.s3

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.brightroom.garage.shared.model.bucket.BucketListItem
import net.brightroom.garage.web.api.ApiClient
import net.brightroom.garage.web.components.LoadingIndicator
import net.brightroom.garage.web.navigation.Screen

@Stable
class S3BrowserState(private val scope: CoroutineScope) {
    var buckets by mutableStateOf<List<BucketListItem>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set

    fun refresh() {
        scope.launch {
            try {
                buckets = ApiClient.json.decodeFromString<List<BucketListItem>>(ApiClient.get("/buckets"))
            } catch (_: Exception) {}
            loading = false
        }
    }
}

@Composable
fun rememberS3BrowserState(): S3BrowserState {
    val scope = rememberCoroutineScope()
    return remember { S3BrowserState(scope) }
}

@Composable
fun S3BrowserScreen(onNavigate: (Screen) -> Unit) {
    val state = rememberS3BrowserState()

    LaunchedEffect(Unit) { state.refresh() }

    S3BrowserContent(
        buckets = state.buckets,
        loading = state.loading,
        onNavigate = onNavigate,
    )
}

@Composable
fun S3BrowserContent(
    buckets: List<BucketListItem>,
    loading: Boolean,
    modifier: Modifier = Modifier,
    onNavigate: (Screen) -> Unit,
) {
    if (loading) {
        LoadingIndicator()
        return
    }

    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
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
                    Text("Browse ->", color = MaterialTheme.colorScheme.primary)
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
