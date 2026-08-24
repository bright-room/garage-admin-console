@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package net.brightroom.garage.web.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * タブが隠れているか。
 *
 * `document.hidden` は kotlinx-browser の wasmJs 向け Document に無く、
 * `visibilityState` も external な列挙型で扱いが不安定なため直接参照する。
 */
private fun isDocumentHidden(): Boolean = js("document.hidden")

/**
 * 定期取得の状態（spec §8.5）。
 *
 * 経過時間はこの型が進めるが、0 に戻すのは画面の役目である（[markUpdated]）。
 * 取得に失敗したときも「最終更新 0 秒前」と出してしまうと、古いデータを
 * 新しいと偽ることになるため。
 */
class PollingState internal constructor(internal val intervalMillis: Long) {

    var autoRefresh: Boolean by mutableStateOf(true)

    var secondsSinceUpdate: Int by mutableStateOf(0)
        internal set

    /** 取得に成功した画面が呼ぶ。 */
    fun markUpdated() {
        secondsSinceUpdate = 0
    }
}

/**
 * [intervalMillis] ごとに [load] を呼ぶ。最初の 1 回は即座に呼ぶ。
 *
 * 自動更新が切られている間と、タブが隠れている間は呼ばない。放置された
 * タブが Garage を叩き続けないようにするためである。
 */
@Composable
fun rememberPolling(intervalMillis: Long, load: suspend () -> Unit): PollingState {
    val polling = remember(intervalMillis) { PollingState(intervalMillis) }

    // load はコンポジションごとに新しいラムダになりうる。毎回 LaunchedEffect を
    // 作り直すとポーリングが再開されてしまうため、最新の参照だけを差し替える
    val currentLoad by rememberUpdatedState(load)

    LaunchedEffect(polling) {
        currentLoad()

        while (true) {
            delay(1_000)
            polling.secondsSinceUpdate++

            if (polling.autoRefresh &&
                polling.secondsSinceUpdate * 1_000L >= polling.intervalMillis &&
                !isDocumentHidden()
            ) {
                currentLoad()
            }
        }
    }

    return polling
}

/**
 * 画面の見出しと、自動更新のトグル・最終更新・手動更新（spec §8.5）。
 *
 * @param trailing 更新ボタンの右に足す操作。画面固有のボタンを置く。
 */
@Composable
fun PollingHeader(
    title: String,
    polling: PollingState,
    onRefresh: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        Text(
            "最終更新 ${polling.secondsSinceUpdate} 秒前",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("自動更新", style = MaterialTheme.typography.bodySmall)
        Switch(checked = polling.autoRefresh, onCheckedChange = { polling.autoRefresh = it })
        TextButton(onClick = onRefresh) { Text("更新") }
        trailing()
    }
}
