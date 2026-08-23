package net.brightroom.garage.web.screens.buckets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import kotlinx.serialization.builtins.ListSerializer
import net.brightroom.garage.shared.api.BucketAliasRequest
import net.brightroom.garage.shared.api.BucketKeyPermissionRequest
import net.brightroom.garage.shared.api.CleanupUploadsRequest
import net.brightroom.garage.shared.api.CleanupUploadsResult
import net.brightroom.garage.shared.api.UpdateBucketRequest
import net.brightroom.garage.shared.model.garage.BucketInfo
import net.brightroom.garage.shared.model.garage.BucketKey
import net.brightroom.garage.shared.model.garage.BucketKeyPermissions
import net.brightroom.garage.shared.model.garage.KeySummary
import net.brightroom.garage.shared.navigation.percentEncode
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
import net.brightroom.garage.web.components.formatBytes
import net.brightroom.garage.web.session.LocalSession

@Composable
fun BucketDetailScreen(
    bucketId: String,
    onOpenObjects: (String) -> Unit,
    onOpenKey: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var bucket by remember(bucketId) { mutableStateOf<BucketInfo?>(null) }
    var failure by remember(bucketId) { mutableStateOf<ApiResult.Failure?>(null) }
    var notice by remember(bucketId) { mutableStateOf<Notice?>(null) }
    var deleting by remember(bucketId) { mutableStateOf(false) }

    suspend fun load() {
        when (val result = session.api.getJson("/api/buckets/$bucketId", BucketInfo.serializer())) {
            is ApiResult.Success -> {
                bucket = result.value
                failure = null
            }

            is ApiResult.Failure -> failure = result

            ApiResult.Unauthorized -> session.invalidate()
        }
    }

    /** 変更系の共通後処理。成功したら読み直し、失敗したら理由を出す。 */
    suspend fun apply(result: ApiResult<*>, success: String? = null) {
        when (result) {
            is ApiResult.Success -> {
                notice = success?.let { Notice(it) }
                load()
            }

            is ApiResult.Failure -> notice = Notice(result.problem.displayMessage, failed = true)

            ApiResult.Unauthorized -> session.invalidate()
        }
    }

    LaunchedEffect(bucketId) { load() }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val current = bucket

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                current?.displayName ?: "バケット",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { scope.launch { load() } }) { Text("更新") }
            if (current != null) {
                Button(onClick = { onOpenObjects(current.id) }) { Text("オブジェクトを見る") }
            }
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

        Overview(current)

        AliasSection(
            bucket = current,
            onAdd = { alias, clearInput ->
                scope.launch {
                    val result = session.api.sendJson(
                        HttpMethod.Post,
                        "/api/buckets/$bucketId/aliases",
                        AppJson.encodeToString(BucketAliasRequest.serializer(), BucketAliasRequest(alias)),
                        BucketInfo.serializer(),
                    )

                    if (result is ApiResult.Success) clearInput()
                    apply(result, success = "別名 $alias を追加しました")
                }
            },
            onRemove = { alias ->
                scope.launch {
                    apply(
                        session.api.sendEmpty(
                            HttpMethod.Delete,
                            "/api/buckets/$bucketId/aliases?alias=${percentEncode(alias)}",
                        ),
                        success = "別名 $alias を削除しました",
                    )
                }
            },
        )

        KeySection(
            bucket = current,
            onOpenKey = onOpenKey,
            onGrant = { key, permissions ->
                scope.launch {
                    apply(
                        session.api.sendJson(
                            HttpMethod.Put,
                            "/api/buckets/$bucketId/keys/${key.accessKeyId}",
                            AppJson.encodeToString(
                                BucketKeyPermissionRequest.serializer(),
                                BucketKeyPermissionRequest(permissions),
                            ),
                            BucketInfo.serializer(),
                        ),
                        success = "${key.name} に権限を付与しました",
                    )
                }
            },
            onGranted = { name ->
                notice = Notice("$name に権限を付与しました")
                scope.launch { load() }
            },
            onRevoke = { key ->
                scope.launch {
                    apply(
                        session.api.sendEmpty(
                            HttpMethod.Delete,
                            "/api/buckets/$bucketId/keys/${key.accessKeyId}",
                        ),
                        success = "${key.name} の権限をすべて外しました",
                    )
                }
            },
        )

        BucketSettingsForm(current) { request ->
            scope.launch {
                apply(
                    session.api.sendJson(
                        HttpMethod.Patch,
                        "/api/buckets/$bucketId",
                        AppJson.encodeToString(UpdateBucketRequest.serializer(), request),
                        BucketInfo.serializer(),
                    ),
                    success = "設定を保存しました",
                )
            }
        }

        MaintenanceSection(
            bucket = current,
            onCleanup = {
                scope.launch {
                    val result = session.api.sendJson(
                        HttpMethod.Post,
                        "/api/buckets/$bucketId/cleanup-uploads",
                        AppJson.encodeToString(
                            CleanupUploadsRequest.serializer(),
                            CleanupUploadsRequest(),
                        ),
                        CleanupUploadsResult.serializer(),
                    )

                    val message = (result as? ApiResult.Success)?.value
                        ?.let { "${it.uploadsDeleted} 件の未完了アップロードを削除しました" }
                    apply(result, success = message)
                }
            },
            onDelete = { deleting = true },
        )
    }

    if (deleting && bucket != null) {
        val target = bucket!!
        ConfirmDialog(
            title = "バケットを削除",
            message = "${target.displayName} を削除します。中身が残っている場合、Garage は削除を拒否します。",
            requiredInput = target.displayName,
            onDismiss = { deleting = false },
            onConfirm = {
                deleting = false
                scope.launch {
                    when (val result = session.api.sendEmpty(HttpMethod.Delete, "/api/buckets/$bucketId")) {
                        is ApiResult.Success -> onDeleted()
                        is ApiResult.Failure -> notice = Notice(result.problem.displayMessage, failed = true)
                        ApiResult.Unauthorized -> session.invalidate()
                    }
                }
            },
        )
    }
}

/** [BucketDetailScreen] と [net.brightroom.garage.web.screens.keys.KeyDetailScreen] の notice 表示で共有する。 */
internal data class Notice(val message: String, val failed: Boolean = false)

@Composable
internal fun BucketSection(title: String, content: @Composable () -> Unit) {
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

@Composable
private fun Overview(bucket: BucketInfo) {
    BucketSection("概要") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ID", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(bucket.id, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(4f))
            CopyButton(bucket.id)
        }

        Text("オブジェクト ${bucket.objects} 件 ・ ${formatBytes(bucket.bytes)}")

        if (bucket.unfinishedUploads > 0) {
            Text(
                "未完了のアップロード ${bucket.unfinishedUploads} 件" +
                    "（マルチパート ${bucket.unfinishedMultipartUploads} 件 / " +
                    "${formatBytes(bucket.unfinishedMultipartUploadBytes)}）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AliasSection(bucket: BucketInfo, onAdd: (String, () -> Unit) -> Unit, onRemove: (String) -> Unit) {
    var newAlias by remember { mutableStateOf("") }

    BucketSection("別名") {
        if (bucket.globalAliases.isEmpty()) {
            Text(
                "グローバル別名がありません。S3 API から参照するには別名が要ります",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        bucket.globalAliases.forEach { alias ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(alias, modifier = Modifier.weight(1f))
                TextButton(onClick = { onRemove(alias) }) { Text("別名を削除") }
            }
        }

        // ローカル別名は表示のみ（P2-6）。追加には対象キーの指定が要り、UI が煩雑になる
        bucket.keys.flatMap { key -> key.bucketLocalAliases.map { key.name to it } }
            .forEach { (keyName, alias) ->
                Text(
                    "$alias（$keyName のローカル別名）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = newAlias,
                onValueChange = { newAlias = it },
                label = { Text("追加する別名") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = newAlias.isNotBlank(),
                onClick = { onAdd(newAlias) { newAlias = "" } },
            ) {
                Text("追加")
            }
        }
    }
}

/**
 * アクセスキーの権限。
 *
 * Garage の `AllowBucketKey` は true のフラグを付与するだけで、false は無視され
 * 既存の権限が保たれる（部分的な剥奪はできない）。そのため既に持っている権限は
 * チェック済み・操作不可で示し、**持っていない権限だけ**選んで付与できるように
 * する。剥奪は全部を外す（P2-11）。減らしたいときは外してから必要な権限で付け直す。
 *
 * 「権限を外す」を押すと、そのキーは `bucket.keys` から消える（実機で確認済み。
 * `DenyBucketKey` 後は `GetBucketInfo.keys` から該当エントリが無くなり、取り直しても
 * 現れない）。付け直す経路が `bucket.keys` の上（このセクションの行）にしか無いと
 * 行き止まりになるため、まだ権限を持っていないキーにも付与できる
 * 「キーに権限を付与」を別に用意する（[GrantKeyDialog]）。
 */
@Composable
private fun KeySection(
    bucket: BucketInfo,
    onOpenKey: (String) -> Unit,
    onGrant: (BucketKey, BucketKeyPermissions) -> Unit,
    onGranted: (String) -> Unit,
    onRevoke: (BucketKey) -> Unit,
) {
    var revoking by remember { mutableStateOf<BucketKey?>(null) }
    var granting by remember { mutableStateOf(false) }

    BucketSection("アクセスキー") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "権限を減らすことはできません。減らすには「権限を外す」を押してください。" +
                    "外すとこのキーは一覧から消えるので、付け直すには「キーに権限を付与」から選び直してください",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { granting = true }) { Text("キーに権限を付与") }
        }

        if (bucket.keys.isEmpty()) {
            Text(
                "このバケットに権限を持つキーがありません。オブジェクトの操作にはキーが要ります",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        bucket.keys.forEach { key ->
            var read by remember(key.accessKeyId, key.permissions) { mutableStateOf(key.permissions.read) }
            var write by remember(key.accessKeyId, key.permissions) { mutableStateOf(key.permissions.write) }
            var owner by remember(key.accessKeyId, key.permissions) { mutableStateOf(key.permissions.owner) }

            val hasNewGrant = (read && !key.permissions.read) ||
                (write && !key.permissions.write) ||
                (owner && !key.permissions.owner)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { onOpenKey(key.accessKeyId) }, modifier = Modifier.weight(2f)) {
                    Text(key.name)
                }

                Checkbox(checked = read, enabled = !key.permissions.read, onCheckedChange = { read = it })
                Text("read", style = MaterialTheme.typography.labelSmall)
                Checkbox(checked = write, enabled = !key.permissions.write, onCheckedChange = { write = it })
                Text("write", style = MaterialTheme.typography.labelSmall)
                Checkbox(checked = owner, enabled = !key.permissions.owner, onCheckedChange = { owner = it })
                Text("owner", style = MaterialTheme.typography.labelSmall)

                Button(
                    enabled = hasNewGrant,
                    onClick = {
                        onGrant(key, BucketKeyPermissions(owner = owner, read = read, write = write))
                    },
                ) {
                    Text("権限を付与")
                }

                OutlinedButton(onClick = { revoking = key }) { Text("権限を外す") }
            }
        }
    }

    revoking?.let { key ->
        ConfirmDialog(
            title = "権限を外す",
            message = "${key.name}（${key.accessKeyId}）から ${bucket.displayName} の権限をすべて外します。" +
                "外すとこのキーはこのバケットの一覧から消えます。付け直すには「キーに権限を付与」から選び直してください。",
            onDismiss = { revoking = null },
            onConfirm = {
                revoking = null
                onRevoke(key)
            },
        )
    }

    if (granting) {
        GrantKeyDialog(
            bucketId = bucket.id,
            excludedKeyIds = bucket.keys.map { it.accessKeyId }.toSet(),
            onDismiss = { granting = false },
            onGranted = { name ->
                granting = false
                onGranted(name)
            },
        )
    }
}

/**
 * まだこのバケットに権限を持っていないキーを選び、権限を付与する。
 *
 * `bucket.keys` に居ないキー（このバケットへの権限を一度も持ったことが無いキーだけ
 * でなく、`KeySection` の「権限を外す」で消えたキーも含む）を `GET /api/keys` の
 * 全件から選べるようにする。付与は既存の行の「権限を付与」と同じ grant-only の
 * `PUT /api/buckets/{bucketId}/keys/{accessKeyId}`（P2-11）。
 */
@Composable
private fun GrantKeyDialog(
    bucketId: String,
    excludedKeyIds: Set<String>,
    onDismiss: () -> Unit,
    onGranted: (String) -> Unit,
) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var keys by remember { mutableStateOf<List<KeySummary>?>(null) }
    var keysFailure by remember { mutableStateOf<ApiResult.Failure?>(null) }
    var selectedKeyId by remember { mutableStateOf<String?>(null) }
    var read by remember { mutableStateOf(false) }
    var write by remember { mutableStateOf(false) }
    var owner by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        when (val result = session.api.getJson("/api/keys", ListSerializer(KeySummary.serializer()))) {
            is ApiResult.Success -> keys = result.value
            is ApiResult.Failure -> keysFailure = result
            ApiResult.Unauthorized -> session.invalidate()
        }
    }

    val available = keys?.filterNot { it.id in excludedKeyIds }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("バケットへのキーの権限付与") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    keysFailure != null -> ProblemView(keysFailure!!.problem, keysFailure!!.status)

                    keys == null -> LoadingView()

                    available.isNullOrEmpty() -> Text(
                        "権限を付与できるキーがありません",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> {
                        Column(
                            modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                        ) {
                            available.forEach { key ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = selectedKeyId == key.id,
                                        onClick = { selectedKeyId = key.id },
                                    )
                                    Text(key.name)
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = read, onCheckedChange = { read = it })
                            Text("read", style = MaterialTheme.typography.labelSmall)
                            Checkbox(checked = write, onCheckedChange = { write = it })
                            Text("write", style = MaterialTheme.typography.labelSmall)
                            Checkbox(checked = owner, onCheckedChange = { owner = it })
                            Text("owner", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !sending && selectedKeyId != null && (read || write || owner),
                onClick = {
                    val keyId = selectedKeyId ?: return@TextButton
                    val keyName = available?.firstOrNull { it.id == keyId }?.name ?: return@TextButton
                    sending = true
                    scope.launch {
                        val body = AppJson.encodeToString(
                            BucketKeyPermissionRequest.serializer(),
                            BucketKeyPermissionRequest(
                                BucketKeyPermissions(owner = owner, read = read, write = write),
                            ),
                        )

                        when (
                            val result = session.api.sendJson(
                                HttpMethod.Put,
                                "/api/buckets/$bucketId/keys/$keyId",
                                body,
                                BucketInfo.serializer(),
                            )
                        ) {
                            is ApiResult.Success -> onGranted(keyName)

                            is ApiResult.Failure -> {
                                error = result.problem.displayMessage
                                sending = false
                            }

                            ApiResult.Unauthorized -> session.invalidate()
                        }
                    }
                },
            ) {
                Text("権限を付与")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
private fun MaintenanceSection(bucket: BucketInfo, onCleanup: () -> Unit, onDelete: () -> Unit) {
    var confirmingCleanup by remember { mutableStateOf(false) }

    BucketSection("メンテナンス") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "24 時間より古い未完了のアップロードを削除します",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { confirmingCleanup = true }) { Text("未完了アップロードを削除") }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "バケットを削除します。中身が残っていると Garage は拒否します",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onDelete) {
                Text("バケットを削除", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmingCleanup) {
        ConfirmDialog(
            title = "未完了アップロードの後始末",
            message = "${bucket.displayName} で 24 時間より古い未完了のアップロードを削除します。" +
                "進行中のアップロードには影響しません。",
            onDismiss = { confirmingCleanup = false },
            onConfirm = {
                confirmingCleanup = false
                onCleanup()
            },
        )
    }
}
