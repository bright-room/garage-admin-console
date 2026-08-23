package net.brightroom.garage.web.screens.buckets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.brightroom.garage.shared.api.UpdateBucketRequest
import net.brightroom.garage.shared.api.WebsiteAccessRequest
import net.brightroom.garage.shared.model.garage.AbortIncompleteMultipartUpload
import net.brightroom.garage.shared.model.garage.BucketInfo
import net.brightroom.garage.shared.model.garage.BucketQuotas
import net.brightroom.garage.shared.model.garage.CorsRule
import net.brightroom.garage.shared.model.garage.FilterConditions
import net.brightroom.garage.shared.model.garage.LifecycleExpiration
import net.brightroom.garage.shared.model.garage.LifecycleRule
import net.brightroom.garage.shared.model.garage.LifecycleStatus
import net.brightroom.garage.shared.model.garage.toConditions

/** CORS で選べるメソッド。S3 が受け付けるものに限る。 */
private val CORS_METHODS = listOf("GET", "PUT", "POST", "DELETE", "HEAD")

/**
 * バケットの設定。
 *
 * **4 つの設定はそれぞれ独立して保存する。** `UpdateBucket` は省略したフィールドを
 * 変更しないため、quota を保存しても CORS やライフサイクルには触れない。
 * まとめて送ると、触っていない設定まで書き戻すことになる。
 */
@Composable
internal fun BucketSettingsForm(bucket: BucketInfo, onSave: (UpdateBucketRequest) -> Unit) {
    QuotaForm(bucket, onSave)
    WebsiteForm(bucket, onSave)
    CorsForm(bucket, onSave)
    LifecycleForm(bucket, onSave)
}

@Composable
private fun QuotaForm(bucket: BucketInfo, onSave: (UpdateBucketRequest) -> Unit) {
    var maxObjects by remember(bucket.quotas) { mutableStateOf(bucket.quotas.maxObjects?.toString().orEmpty()) }
    var maxSize by remember(bucket.quotas) { mutableStateOf(bucket.quotas.maxSize?.toString().orEmpty()) }

    BucketSection("上限") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = maxObjects,
                onValueChange = { maxObjects = it.filter(Char::isDigit) },
                label = { Text("最大オブジェクト数") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = maxSize,
                onValueChange = { maxSize = it.filter(Char::isDigit) },
                label = { Text("最大サイズ（バイト）") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            "空欄にして保存すると上限を解除します。Garage は 2 つをまとめて扱うため、片方だけの解除はできません",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onSave(
                        UpdateBucketRequest(
                            quotas = BucketQuotas(
                                maxObjects = maxObjects.toLongOrNull(),
                                maxSize = maxSize.toLongOrNull(),
                            ),
                        ),
                    )
                },
            ) {
                Text("保存")
            }
        }
    }
}

@Composable
private fun WebsiteForm(bucket: BucketInfo, onSave: (UpdateBucketRequest) -> Unit) {
    var enabled by remember(bucket.websiteAccess, bucket.websiteConfig) { mutableStateOf(bucket.websiteAccess) }
    var index by remember(bucket.websiteAccess, bucket.websiteConfig) {
        mutableStateOf(bucket.websiteConfig?.indexDocument ?: "index.html")
    }
    var error by remember(bucket.websiteAccess, bucket.websiteConfig) {
        mutableStateOf(bucket.websiteConfig?.errorDocument.orEmpty())
    }

    BucketSection("公開") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = enabled, onCheckedChange = { enabled = it })
            Text("website として公開する")
        }

        if (enabled) {
            OutlinedTextField(
                value = index,
                onValueChange = { index = it },
                label = { Text("インデックスドキュメント") },
                singleLine = true,
            )
            OutlinedTextField(
                value = error,
                onValueChange = { error = it },
                label = { Text("エラードキュメント（任意）") },
                singleLine = true,
            )
        }

        Button(
            enabled = !enabled || index.isNotBlank(),
            onClick = {
                // 無効にするときはドキュメントを送ってはならない（Garage が拒否する）
                val request = if (enabled) {
                    WebsiteAccessRequest(
                        enabled = true,
                        indexDocument = index,
                        errorDocument = error.ifBlank { null },
                    )
                } else {
                    WebsiteAccessRequest(enabled = false)
                }

                onSave(UpdateBucketRequest(websiteAccess = request))
            },
        ) {
            Text("保存")
        }
    }
}

/** 編集中の CORS ルール。文字列のまま持ち、保存のときに [CorsRule] へ写す。 */
private class CorsDraft(rule: CorsRule) {
    var id by mutableStateOf(rule.id.orEmpty())
    var origins by mutableStateOf(rule.allowedOrigins.joinToString(", "))
    var headers by mutableStateOf(rule.allowedHeaders.joinToString(", "))
    var exposed by mutableStateOf(rule.exposeHeaders.joinToString(", "))
    var maxAge by mutableStateOf(rule.maxAgeSeconds?.toString().orEmpty())
    val methods = mutableStateListOf<String>().apply { addAll(rule.allowedMethods) }

    fun toRule() = CorsRule(
        allowedOrigins = origins.splitList(),
        allowedMethods = methods.toList(),
        allowedHeaders = headers.splitList(),
        exposeHeaders = exposed.splitList(),
        maxAgeSeconds = maxAge.toLongOrNull(),
        id = id.ifBlank { null },
    )
}

private fun String.splitList(): List<String> = split(',').map(String::trim).filter(String::isNotEmpty)

@Composable
private fun CorsForm(bucket: BucketInfo, onSave: (UpdateBucketRequest) -> Unit) {
    val drafts = remember(bucket.corsRules) {
        mutableStateListOf<CorsDraft>().apply {
            addAll(bucket.corsRules.orEmpty().map(::CorsDraft))
        }
    }

    BucketSection("CORS") {
        if (drafts.isEmpty()) {
            Text(
                "ルールがありません。保存すると設定を削除します",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        drafts.forEachIndexed { index, draft ->
            if (index > 0) HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = draft.id,
                        onValueChange = { draft.id = it },
                        label = { Text("ID（任意）") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = draft.maxAge,
                        onValueChange = { draft.maxAge = it.filter(Char::isDigit) },
                        label = { Text("MaxAgeSeconds") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                OutlinedTextField(
                    value = draft.origins,
                    onValueChange = { draft.origins = it },
                    label = { Text("許可するオリジン（カンマ区切り）") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    CORS_METHODS.forEach { method ->
                        Checkbox(
                            checked = draft.methods.contains(method),
                            onCheckedChange = { checked ->
                                if (checked) draft.methods.add(method) else draft.methods.remove(method)
                            },
                        )
                        Text(method, style = MaterialTheme.typography.labelSmall)
                    }
                }

                OutlinedTextField(
                    value = draft.headers,
                    onValueChange = { draft.headers = it },
                    label = { Text("許可するヘッダ（カンマ区切り）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.exposed,
                    onValueChange = { draft.exposed = it },
                    label = { Text("公開するヘッダ（カンマ区切り）") },
                    modifier = Modifier.fillMaxWidth(),
                )

                TextButton(onClick = { drafts.remove(draft) }) { Text("このルールを削除") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    drafts.add(
                        CorsDraft(CorsRule(allowedOrigins = listOf("*"), allowedMethods = listOf("GET"))),
                    )
                },
            ) {
                Text("ルールを追加")
            }
            Button(onClick = { onSave(UpdateBucketRequest(corsRules = drafts.map { it.toRule() })) }) {
                Text("保存")
            }
        }
    }
}

/** 編集中のライフサイクルルール。 */
private class LifecycleDraft(rule: LifecycleRule) {
    var id by mutableStateOf(rule.id.orEmpty())
    var enabled by mutableStateOf(rule.status == LifecycleStatus.ENABLED)
    var prefix by mutableStateOf(rule.filter.toConditions().prefix.orEmpty())
    var sizeGreaterThan by mutableStateOf(
        rule.filter.toConditions().sizeGreaterThan?.toString().orEmpty(),
    )
    var sizeLessThan by mutableStateOf(
        rule.filter.toConditions().sizeLessThan?.toString().orEmpty(),
    )
    var expireByDays by mutableStateOf(rule.expiration?.date == null)
    var days by mutableStateOf(rule.expiration?.days?.toString().orEmpty())
    var date by mutableStateOf(rule.expiration?.date.orEmpty())
    var abortDays by mutableStateOf(
        rule.abortIncompleteMultipartUpload?.daysAfterInitiation?.toString().orEmpty(),
    )

    fun toRule() = LifecycleRule(
        status = if (enabled) LifecycleStatus.ENABLED else LifecycleStatus.DISABLED,
        id = id.ifBlank { null },
        filter = FilterConditions(
            prefix = prefix.ifBlank { null },
            sizeGreaterThan = sizeGreaterThan.toLongOrNull(),
            sizeLessThan = sizeLessThan.toLongOrNull(),
        ).toFilter(),
        expiration = expiration(),
        abortIncompleteMultipartUpload = abortDays.toLongOrNull()
            ?.let(::AbortIncompleteMultipartUpload),
    )

    /** 日数と日付は排他。どちらも空なら期限を設けない。 */
    private fun expiration(): LifecycleExpiration? = when {
        expireByDays -> days.toLongOrNull()?.let { LifecycleExpiration(days = it) }
        date.isNotBlank() -> LifecycleExpiration(date = date)
        else -> null
    }
}

@Composable
private fun LifecycleForm(bucket: BucketInfo, onSave: (UpdateBucketRequest) -> Unit) {
    val drafts = remember(bucket.lifecycleRules) {
        mutableStateListOf<LifecycleDraft>().apply {
            addAll(bucket.lifecycleRules.orEmpty().map(::LifecycleDraft))
        }
    }

    BucketSection("ライフサイクル") {
        if (drafts.isEmpty()) {
            Text(
                "ルールがありません。保存すると設定を削除します",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        drafts.forEachIndexed { index, draft ->
            if (index > 0) HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = draft.id,
                        onValueChange = { draft.id = it },
                        label = { Text("ID（任意）") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = draft.enabled, onCheckedChange = { draft.enabled = it })
                    Text(if (draft.enabled) "有効" else "無効")
                }

                OutlinedTextField(
                    value = draft.prefix,
                    onValueChange = { draft.prefix = it },
                    label = { Text("対象の接頭辞（任意）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = draft.sizeGreaterThan,
                        onValueChange = { draft.sizeGreaterThan = it.filter(Char::isDigit) },
                        label = { Text("これより大きい（バイト）") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = draft.sizeLessThan,
                        onValueChange = { draft.sizeLessThan = it.filter(Char::isDigit) },
                        label = { Text("これより小さい（バイト）") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = draft.expireByDays, onClick = { draft.expireByDays = true })
                    Text("日数で削除")
                    RadioButton(selected = !draft.expireByDays, onClick = { draft.expireByDays = false })
                    Text("日付で削除")
                }

                if (draft.expireByDays) {
                    OutlinedTextField(
                        value = draft.days,
                        onValueChange = { draft.days = it.filter(Char::isDigit) },
                        label = { Text("作成から何日後に削除するか") },
                        singleLine = true,
                    )
                } else {
                    OutlinedTextField(
                        value = draft.date,
                        onValueChange = { draft.date = it },
                        label = { Text("削除する日時（例 2027-01-01T00:00:00Z）") },
                        singleLine = true,
                    )
                }

                OutlinedTextField(
                    value = draft.abortDays,
                    onValueChange = { draft.abortDays = it.filter(Char::isDigit) },
                    label = { Text("未完了アップロードを打ち切るまでの日数（任意）") },
                    singleLine = true,
                )

                TextButton(onClick = { drafts.remove(draft) }) { Text("このルールを削除") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { drafts.add(LifecycleDraft(LifecycleRule(status = LifecycleStatus.ENABLED))) },
            ) {
                Text("ルールを追加")
            }
            Button(
                onClick = { onSave(UpdateBucketRequest(lifecycleRules = drafts.map { it.toRule() })) },
            ) {
                Text("保存")
            }
        }
    }
}
