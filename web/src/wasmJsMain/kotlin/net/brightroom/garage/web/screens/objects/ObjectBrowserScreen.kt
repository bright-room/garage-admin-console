package net.brightroom.garage.web.screens.objects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.launch
import net.brightroom.garage.shared.api.ProblemDetails
import net.brightroom.garage.shared.api.ProblemTypes
import net.brightroom.garage.shared.model.garage.ObjectInspection
import net.brightroom.garage.shared.model.s3.ObjectListing
import net.brightroom.garage.shared.model.s3.StoredObject
import net.brightroom.garage.shared.model.s3.parentPrefix
import net.brightroom.garage.shared.navigation.percentEncode
import net.brightroom.garage.web.api.ApiResult
import net.brightroom.garage.web.api.AppJson
import net.brightroom.garage.web.api.displayMessage
import net.brightroom.garage.web.api.getJson
import net.brightroom.garage.web.api.sendEmpty
import net.brightroom.garage.web.components.ConfirmDialog
import net.brightroom.garage.web.components.DataTable
import net.brightroom.garage.web.components.EmptyState
import net.brightroom.garage.web.components.LoadingView
import net.brightroom.garage.web.components.ProblemView
import net.brightroom.garage.web.components.TableColumn
import net.brightroom.garage.web.components.formatBytes
import net.brightroom.garage.web.session.LocalSession

/**
 * オブジェクトブラウザ。
 *
 * 一覧は手動更新のみ（spec §8.5）。表示中のフォルダは URL のクエリに載るため、
 * リロードとブックマークで同じ場所に戻れる。
 */
@Composable
fun ObjectBrowserScreen(
    bucketId: String,
    prefix: String,
    onNavigatePrefix: (String) -> Unit,
    onOpenBucket: (String) -> Unit,
) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var listing by remember(bucketId, prefix) { mutableStateOf<ObjectListing?>(null) }
    var failure by remember(bucketId, prefix) { mutableStateOf<ApiResult.Failure?>(null) }
    var notice by remember(bucketId, prefix) { mutableStateOf<String?>(null) }
    var deleting by remember(bucketId, prefix) { mutableStateOf<StoredObject?>(null) }
    var inspecting by remember(bucketId, prefix) { mutableStateOf<ObjectInspection?>(null) }
    var busy by remember(bucketId, prefix) { mutableStateOf(false) }

    val listPath = "/api/buckets/$bucketId/objects?prefix=${percentEncode(prefix)}"

    suspend fun load(continuation: String? = null) {
        val path = continuation?.let { "$listPath&token=${percentEncode(it)}" } ?: listPath

        when (val result = session.api.getJson(path, ObjectListing.serializer())) {
            is ApiResult.Success -> {
                // 続きを読んだときは前のページに足す
                listing = listing
                    ?.takeIf { continuation != null }
                    ?.let { previous ->
                        result.value.copy(
                            folders = previous.folders + result.value.folders,
                            objects = previous.objects + result.value.objects,
                        )
                    }
                    ?: result.value
                failure = null
            }

            is ApiResult.Failure -> failure = result

            ApiResult.Unauthorized -> session.invalidate()
        }
    }

    /**
     * 転送の結果を `ApiClient` と同じ基準で扱う。
     *
     * @param reload 成功時に一覧を再読込するか。アップロード・削除は結果が一覧に
     *   反映されるため再読込するが、ダウンロードは読み取り専用で一覧に影響しない。
     */
    fun handle(outcome: TransferOutcome, reload: Boolean = true, onDone: (String) -> String) {
        when (outcome) {
            is TransferOutcome.Done -> {
                notice = onDone(outcome.fileName)
                if (reload) scope.launch { load() }
            }

            TransferOutcome.Cancelled -> notice = null

            is TransferOutcome.Failed -> when (outcome.status) {
                HttpStatusCode.Unauthorized.value -> session.invalidate()
                else -> notice = problemMessage(outcome)
            }
        }
    }

    LaunchedEffect(bucketId, prefix) { load() }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "オブジェクト",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onOpenBucket(bucketId) }) { Text("バケットの設定") }
            TextButton(onClick = { scope.launch { load() } }) { Text("更新") }
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val url = "/api/buckets/$bucketId/objects?key=${percentEncode(prefix)}"
                        val outcome = uploadObject(url, session.token.orEmpty())
                        busy = false
                        handle(outcome) { name -> "$name をアップロードしました" }
                    }
                },
            ) {
                Text("アップロード")
            }
        }

        Breadcrumbs(prefix, onNavigatePrefix)

        notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        failure?.let { current ->
            DegradedView(current, onOpenBucket = { onOpenBucket(bucketId) }, onRetry = { scope.launch { load() } })
        }

        val current = listing

        when {
            current == null -> if (failure == null) LoadingView()

            current.isEmpty -> EmptyState(
                if (prefix.isEmpty()) {
                    "このバケットにオブジェクトはありません"
                } else {
                    "このフォルダにオブジェクトはありません"
                },
            )

            else -> ObjectTable(
                listing = current,
                onOpenFolder = onNavigatePrefix,
                onDownload = { obj ->
                    busy = true
                    scope.launch {
                        val url = "/api/buckets/$bucketId/objects/content?key=${percentEncode(obj.key)}"
                        val outcome = downloadObject(url, session.token.orEmpty(), obj.nameIn(prefix))
                        busy = false
                        handle(outcome, reload = false) { name -> "$name をダウンロードしました" }
                    }
                },
                onInspect = { obj ->
                    scope.launch {
                        val path = "/api/buckets/$bucketId/objects/inspect?key=${percentEncode(obj.key)}"

                        when (val result = session.api.getJson(path, ObjectInspection.serializer())) {
                            is ApiResult.Success -> inspecting = result.value
                            is ApiResult.Failure -> notice = result.problem.displayMessage
                            ApiResult.Unauthorized -> session.invalidate()
                        }
                    }
                },
                onDelete = { deleting = it },
            )
        }

        current?.nextToken?.let { token ->
            TextButton(onClick = { scope.launch { load(token) } }) { Text("続きを読み込む") }
        }

        current?.keyName?.let { key ->
            Text(
                "$key として閲覧中",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    deleting?.let { target ->
        ConfirmDialog(
            title = "オブジェクトを削除",
            message = "${target.key} を削除します。取り消せません。",
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                scope.launch {
                    val path = "/api/buckets/$bucketId/objects?key=${percentEncode(target.key)}"

                    when (val result = session.api.sendEmpty(HttpMethod.Delete, path)) {
                        is ApiResult.Success -> {
                            notice = "${target.key} を削除しました"
                            load()
                        }

                        is ApiResult.Failure -> notice = result.problem.displayMessage

                        ApiResult.Unauthorized -> session.invalidate()
                    }
                }
            },
        )
    }

    inspecting?.let { inspection ->
        InspectionDialog(inspection) { inspecting = null }
    }
}

/** 現在地と、上の階層への導線。 */
@Composable
private fun Breadcrumbs(prefix: String, onNavigate: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = { onNavigate("") }) { Text("ルート") }

        var walked = ""
        prefix.trimEnd('/').split('/').filter { it.isNotEmpty() }.forEach { segment ->
            walked += "$segment/"
            val destination = walked

            Text("/", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onNavigate(destination) }) { Text(segment) }
        }

        parentPrefix(prefix)?.let { parent ->
            TextButton(onClick = { onNavigate(parent) }) { Text("上へ") }
        }
    }
}

/**
 * S3 ブラウザだけが使えない状態を、理由に応じて出し分ける（spec §6.4）。
 *
 * 判断に使うのは HTTP のステータスと problem details の `type` である。
 */
@Composable
private fun DegradedView(failure: ApiResult.Failure, onOpenBucket: () -> Unit, onRetry: () -> Unit) {
    when (failure.problem.type) {
        ProblemTypes.NO_USABLE_KEY -> EmptyState(
            message = "このバケットにアクセスできるキーがありません。" +
                "バケットの設定でキーに read 以上の権限を与えてください",
            actionLabel = "バケットの設定を開く",
            onAction = onOpenBucket,
        )

        ProblemTypes.BUCKET_NOT_ADDRESSABLE -> EmptyState(
            message = "このバケットには別名が無いため、S3 API から参照できません。" +
                "バケットの設定でグローバル別名を追加してください",
            actionLabel = "バケットの設定を開く",
            onAction = onOpenBucket,
        )

        else -> ProblemView(failure.problem, failure.status, onRetry = onRetry)
    }
}

@Composable
private fun ObjectTable(
    listing: ObjectListing,
    onOpenFolder: (String) -> Unit,
    onDownload: (StoredObject) -> Unit,
    onInspect: (StoredObject) -> Unit,
    onDelete: (StoredObject) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listing.folders.forEach { folder ->
            TextButton(onClick = { onOpenFolder(folder) }) {
                Text("📁 ${folder.removePrefix(listing.prefix)}")
            }
        }

        DataTable(
            items = listing.objects,
            emptyMessage = "オブジェクトがありません",
            searchPlaceholder = "名前で絞り込み",
            columns = listOf(
                TableColumn(
                    title = "名前",
                    weight = 3f,
                    value = { it.nameIn(listing.prefix) },
                ),
                TableColumn(
                    title = "サイズ",
                    value = { formatBytes(it.size) },
                    comparator = compareBy { it.size },
                ),
                TableColumn(
                    title = "更新",
                    value = { it.lastModified?.toString().orEmpty() },
                    comparator = compareBy { it.lastModified },
                    content = { obj ->
                        Text(
                            obj.lastModified?.toString()?.substringBefore('.') ?: "-",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                ),
                TableColumn(
                    title = "操作",
                    weight = 2f,
                    value = { "" },
                    content = { obj ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { onDownload(obj) }) { Text("取得") }
                            TextButton(onClick = { onInspect(obj) }) { Text("詳細") }
                            TextButton(onClick = { onDelete(obj) }) {
                                Text("削除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    },
                ),
            ),
        )
    }
}

/** `InspectObject` の結果。S3 では見えない内部表現を出す。 */
@Composable
private fun InspectionDialog(inspection: ObjectInspection, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(inspection.key) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (inspection.versions.isEmpty()) {
                    Text("バージョンがありません")
                }

                inspection.versions.forEach { version ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            version.timestamp.toString(),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            listOfNotNull(
                                version.size?.let { formatBytes(it) },
                                "インライン格納".takeIf { version.inline },
                                "アップロード中".takeIf { version.uploading },
                                "中断".takeIf { version.aborted },
                                "削除マーカー".takeIf { version.deleteMarker },
                                "SSE-C 暗号化".takeIf { version.encrypted },
                                "${version.blocks.size} ブロック".takeIf { version.blocks.isNotEmpty() },
                            ).joinToString(" ・ "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        version.headers.forEach { header ->
                            Text(
                                header.joinToString(": "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

/**
 * 転送の失敗を利用者に見せる文言にする。
 *
 * 本文はサーバーが返した problem details である。壊れていたらステータスで代用する。
 */
private fun problemMessage(failure: TransferOutcome.Failed): String {
    if (failure.status == 0) return "サーバーに接続できませんでした"

    return runCatching {
        AppJson.decodeFromString(ProblemDetails.serializer(), failure.body).displayMessage
    }.getOrElse { "転送に失敗しました（HTTP ${failure.status}）" }
}
