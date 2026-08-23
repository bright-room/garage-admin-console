@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package net.brightroom.garage.web.components

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * クリップボードへ書き込む。
 *
 * `kotlinx-browser` の wasmJs 向け Navigator に clipboard が無いため、
 * `js()` で直接呼ぶ（`OverviewScreen` の `document.hidden` と同じやり方）。
 */
private fun writeToClipboard(value: String): Unit = js("navigator.clipboard.writeText(value)")

@Composable
fun CopyButton(value: String, label: String = "コピー") {
    var copied by remember { mutableStateOf(false) }

    TextButton(
        onClick = {
            writeToClipboard(value)
            copied = true
        },
    ) {
        Text(if (copied) "コピーしました" else label)
    }
}
