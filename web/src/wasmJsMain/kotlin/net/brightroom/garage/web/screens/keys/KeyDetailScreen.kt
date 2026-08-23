package net.brightroom.garage.web.screens.keys

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.http.HttpMethod
import kotlinx.coroutines.launch
import net.brightroom.garage.shared.api.UpdateKeyRequest
import net.brightroom.garage.shared.model.garage.KeyInfo
import net.brightroom.garage.web.api.ApiResult
import net.brightroom.garage.web.api.AppJson
import net.brightroom.garage.web.api.displayMessage
import net.brightroom.garage.web.api.getJson
import net.brightroom.garage.web.api.sendEmpty
import net.brightroom.garage.web.api.sendJson
import net.brightroom.garage.web.components.ConfirmDialog
import net.brightroom.garage.web.components.CopyButton
import net.brightroom.garage.web.components.LoadingView
import net.brightroom.garage.web.components.ProblemView
import net.brightroom.garage.web.screens.buckets.Notice
import net.brightroom.garage.web.session.LocalSession

@Composable
fun KeyDetailScreen(keyId: String, onOpenBucket: (String) -> Unit, onDeleted: () -> Unit) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var key by remember(keyId) { mutableStateOf<KeyInfo?>(null) }
    var failure by remember(keyId) { mutableStateOf<ApiResult.Failure?>(null) }
    var notice by remember(keyId) { mutableStateOf<Notice?>(null) }
    var secret by remember(keyId) { mutableStateOf<String?>(null) }
    var deleting by remember(keyId) { mutableStateOf(false) }

    suspend fun load(showSecret: Boolean = false) {
        val path = if (showSecret) "/api/keys/$keyId?showSecret=true" else "/api/keys/$keyId"

        when (val result = session.api.getJson(path, KeyInfo.serializer())) {
            is ApiResult.Success -> {
                key = result.value
                secret = if (showSecret) result.value.secretAccessKey else null
                failure = null
            }

            is ApiResult.Failure -> failure = result

            ApiResult.Unauthorized -> session.invalidate()
        }
    }

    LaunchedEffect(keyId) { load() }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val current = key

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                current?.name ?: "アクセスキー",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { scope.launch { load() } }) { Text("更新") }
        }

        failure?.let { ProblemView(it.problem, it.status, onRetry = { scope.launch { load() } }) }
        notice?.let {
            Text(
                it.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (it.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }

        if (current == null) {
            if (failure == null) LoadingView()
            return@Column
        }

        KeySection("資格情報") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ID", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                Text(current.accessKeyId, modifier = Modifier.weight(4f))
                CopyButton(current.accessKeyId)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("シークレット", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))

                val shown = secret
                if (shown == null) {
                    // 押すまでサーバーは secret を受け取らない（P2-7）
                    OutlinedButton(onClick = { scope.launch { load(showSecret = true) } }) {
                        Text("表示")
                    }
                } else {
                    Text(shown, modifier = Modifier.weight(4f))
                    CopyButton(shown)
                }
            }

            Text(
                if (current.expired) {
                    "このキーは失効しています"
                } else {
                    current.expiration?.let { "期限 $it" } ?: "無期限"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (current.expired) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        SettingsSection(
            key = current,
            onSave = { request ->
                scope.launch {
                    val body = AppJson.encodeToString(UpdateKeyRequest.serializer(), request)

                    when (
                        val result = session.api.sendJson(
                            HttpMethod.Patch,
                            "/api/keys/$keyId",
                            body,
                            KeyInfo.serializer(),
                        )
                    ) {
                        is ApiResult.Success -> {
                            notice = Notice("設定を保存しました")
                            load()
                        }

                        is ApiResult.Failure -> notice = Notice(result.problem.displayMessage, failed = true)

                        ApiResult.Unauthorized -> session.invalidate()
                    }
                }
            },
        )

        KeySection("権限を持つバケット") {
            if (current.buckets.isEmpty()) {
                Text(
                    "このキーがアクセスできるバケットはありません",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            current.buckets.forEach { bucket ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onOpenBucket(bucket.id) }, modifier = Modifier.weight(2f)) {
                        Text(bucket.displayName)
                    }
                    Text(
                        listOfNotNull(
                            "owner".takeIf { bucket.permissions.owner },
                            "read".takeIf { bucket.permissions.read },
                            "write".takeIf { bucket.permissions.write },
                        ).joinToString(" / ").ifEmpty { "権限なし" },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        KeySection("削除") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "このキーを削除します。キーを使っている利用者はアクセスできなくなります",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { deleting = true }) {
                    Text("キーを削除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (deleting && key != null) {
        val target = key!!
        ConfirmDialog(
            title = "アクセスキーを削除",
            message = "${target.name}（${target.accessKeyId}）を削除します。",
            onDismiss = { deleting = false },
            onConfirm = {
                deleting = false
                scope.launch {
                    when (val result = session.api.sendEmpty(HttpMethod.Delete, "/api/keys/$keyId")) {
                        is ApiResult.Success -> onDeleted()
                        is ApiResult.Failure -> notice = Notice(result.problem.displayMessage, failed = true)
                        ApiResult.Unauthorized -> session.invalidate()
                    }
                }
            },
        )
    }
}

@Composable
private fun SettingsSection(key: KeyInfo, onSave: (UpdateKeyRequest) -> Unit) {
    var name by remember(key.name) { mutableStateOf(key.name) }
    var allowCreateBucket by remember(key.permissions.createBucket) { mutableStateOf(key.permissions.createBucket) }

    KeySection("設定") {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("名前") },
            singleLine = true,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = allowCreateBucket, onCheckedChange = { allowCreateBucket = it })
            Text("バケットの作成を許可する")
        }

        Button(
            enabled = name != key.name || allowCreateBucket != key.permissions.createBucket,
            onClick = {
                onSave(
                    UpdateKeyRequest(
                        name = name.takeIf { it != key.name },
                        allowCreateBucket = allowCreateBucket
                            .takeIf { it != key.permissions.createBucket },
                    ),
                )
            },
        ) {
            Text("保存")
        }
    }
}

@Composable
private fun KeySection(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}
