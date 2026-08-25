package net.brightroom.garage.web.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 状態の重さ。色はテーマの役割色に写す。
 *
 * 4 段にとどめるのは、ノード・ワーカー・レイアウト版・ブロックエラーで実際に
 * 必要になったのがこの 4 つだからである。
 */
enum class StatusTone { SUCCESS, WARNING, ERROR, NEUTRAL }

/**
 * 一目で状態が分かる小さなラベル（spec §8.7）。
 *
 * 文言は Garage が返す語をそのまま使う。運用者が CLI や API の出力と
 * 突き合わせられることを優先する。
 */
@Composable
fun StatusChip(label: String, tone: StatusTone) {
    val container = when (tone) {
        StatusTone.SUCCESS -> MaterialTheme.colorScheme.secondaryContainer
        StatusTone.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
        StatusTone.ERROR -> MaterialTheme.colorScheme.errorContainer
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
    }

    val content = when (tone) {
        StatusTone.SUCCESS -> MaterialTheme.colorScheme.onSecondaryContainer
        StatusTone.WARNING -> MaterialTheme.colorScheme.onTertiaryContainer
        StatusTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        modifier = Modifier
            .background(container, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
