package net.brightroom.garage.web.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.brightroom.garage.web.theme.StatusDegraded
import net.brightroom.garage.web.theme.StatusHealthy
import net.brightroom.garage.web.theme.StatusUnavailable

@Composable
fun StatusChip(status: String, modifier: Modifier = Modifier) {
    val color = when (status.lowercase()) {
        "healthy" -> StatusHealthy
        "degraded" -> StatusDegraded
        else -> StatusUnavailable
    }

    SuggestionChip(
        onClick = {},
        label = {
            Text(
                text = status.replaceFirstChar { it.uppercase() },
                color = color,
            )
        },
        modifier = modifier,
    )
}
