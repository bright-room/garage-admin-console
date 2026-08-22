package net.brightroom.garage.web.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ConsoleColors = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnBackground,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    outline = Outline,
    error = Error,
    onError = OnError,
)

/** ダーク固定。ライトテーマは要件に含まれない（spec §8.8）。 */
@Composable
fun GarageAdminTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ConsoleColors,
        content = content,
    )
}
