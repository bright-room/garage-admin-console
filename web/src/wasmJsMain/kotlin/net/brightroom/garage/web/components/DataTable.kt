package net.brightroom.garage.web.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class Column<T>(
    val header: String,
    val width: Dp = 0.dp,
    val weight: Float = 1f,
    val content: @Composable (T) -> Unit,
)

@Composable
fun <T> DataTable(
    items: List<T>,
    columns: List<Column<T>>,
    modifier: Modifier = Modifier,
    onRowClick: ((T) -> Unit)? = null,
) {
    Column(modifier = modifier) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            columns.forEach { col ->
                Box(
                    modifier = if (col.width > 0.dp) Modifier.width(col.width)
                    else Modifier.weight(col.weight),
                ) {
                    Text(
                        col.header,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        HorizontalDivider()

        // Rows
        Column {
            items.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .then(
                            if (onRowClick != null) Modifier.clickable { onRowClick(item) }
                            else Modifier
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    columns.forEach { col ->
                        Box(
                            modifier = if (col.width > 0.dp) Modifier.width(col.width)
                            else Modifier.weight(col.weight),
                        ) {
                            col.content(item)
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            }
        }
    }
}
