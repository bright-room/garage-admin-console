package net.brightroom.garage.web.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 表の 1 列。
 *
 * @param value 絞り込みと既定の並べ替えに使う文字列表現。
 * @param comparator 並べ替えの規則。null なら [value] の辞書順。
 *   数値や日時の列は明示的に渡す（文字列の辞書順では順序が狂うため）。
 * @param content セルの描画。null なら [value] をそのまま出す。
 */
data class Column<T>(
    val title: String,
    val value: (T) -> String,
    val weight: Float = 1f,
    val comparator: Comparator<T>? = null,
    val content: (@Composable (T) -> Unit)? = null,
)

/**
 * 絞り込みと並べ替えを持つ表（spec §8.7）。
 *
 * 状態は表の中に閉じる。画面が持つのは元のデータだけでよい。
 */
@Composable
fun <T> DataTable(
    items: List<T>,
    columns: List<Column<T>>,
    modifier: Modifier = Modifier,
    searchPlaceholder: String = "絞り込み",
    emptyMessage: String = "項目がありません",
    onRowClick: ((T) -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    var sortIndex by remember { mutableStateOf(0) }
    var ascending by remember { mutableStateOf(true) }

    val filtered = items.filter { item ->
        query.isBlank() || columns.any { it.value(item).contains(query, ignoreCase = true) }
    }

    val column = columns.getOrNull(sortIndex)
    val comparator = column?.comparator ?: column?.let { current ->
        compareBy<T> { current.value(it) }
    }
    val sorted = when {
        comparator == null -> filtered
        ascending -> filtered.sortedWith(comparator)
        else -> filtered.sortedWith(comparator.reversed())
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(searchPlaceholder) },
            singleLine = true,
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            columns.forEachIndexed { index, current ->
                val marker = when {
                    index != sortIndex -> ""
                    ascending -> " ▲"
                    else -> " ▼"
                }

                Text(
                    current.title + marker,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(current.weight)
                        .clickable {
                            if (index == sortIndex) ascending = !ascending else sortIndex = index
                        },
                )
            }
        }

        HorizontalDivider()

        if (sorted.isEmpty()) {
            EmptyState(if (items.isEmpty()) emptyMessage else "条件に合う項目がありません")
            return@Column
        }

        sorted.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { base -> onRowClick?.let { base.clickable { it(item) } } ?: base }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                columns.forEach { current ->
                    Column(modifier = Modifier.weight(current.weight)) {
                        current.content?.invoke(item)
                            ?: Text(current.value(item), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            HorizontalDivider()
        }
    }
}
