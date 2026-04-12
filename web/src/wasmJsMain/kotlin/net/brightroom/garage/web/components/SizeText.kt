package net.brightroom.garage.web.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun SizeText(bytes: Long, modifier: Modifier = Modifier) {
    Text(text = formatSize(bytes), modifier = modifier)
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble()
    for (unit in units) {
        value /= 1024.0
        if (value < 1024 || unit == units.last()) {
            val rounded = (value * 10).toLong() / 10.0
            return "$rounded $unit"
        }
    }
    return "$bytes B"
}
