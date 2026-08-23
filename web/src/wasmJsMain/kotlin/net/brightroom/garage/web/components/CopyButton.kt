@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package net.brightroom.garage.web.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.js.JsAny
import kotlin.js.Promise

/**
 * クリップボードへ書き込む。
 *
 * `kotlinx-browser` の wasmJs 向け Navigator に clipboard が無いため、
 * `js()` で直接呼ぶ（`OverviewScreen` の `document.hidden` と同じやり方）。
 */
private fun writeToClipboard(value: String): Promise<JsAny?> = js("navigator.clipboard.writeText(value)")

@Composable
fun CopyButton(value: String, label: String = "コピー") {
    val scope = rememberCoroutineScope()

    // null: 未コピー, true: コピー成功, false: コピー失敗
    var result by remember(value) { mutableStateOf<Boolean?>(null) }
    var copying by remember(value) { mutableStateOf(false) }

    TextButton(
        enabled = !copying,
        onClick = {
            copying = true
            scope.launch {
                result = try {
                    writeToClipboard(value).await()
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    false
                }
                copying = false
            }
        },
    ) {
        Text(
            when (result) {
                true -> "コピーしました"
                false -> "コピーできませんでした"
                null -> label
            },
            color = if (result == false) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
    }
}
