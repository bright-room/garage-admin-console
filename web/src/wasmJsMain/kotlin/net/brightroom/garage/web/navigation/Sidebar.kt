package net.brightroom.garage.web.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.brightroom.garage.shared.api.allows
import net.brightroom.garage.shared.navigation.Route
import net.brightroom.garage.web.session.LocalSession

@Composable
fun Sidebar(
    current: Route,
    onNavigate: (Route) -> Unit,
) {
    val session = LocalSession.current

    Column(
        modifier = Modifier
            .width(240.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 16.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "Garage",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 12.dp, bottom = 12.dp),
        )

        navGroups.forEach { group ->
            group.title?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
                )
            }

            group.items.forEach { item ->
                val enabled = item.requiredOperation == null ||
                    session.info?.allows(item.requiredOperation) == true

                NavigationDrawerItem(
                    label = {
                        Text(
                            if (enabled) item.label else "${item.label}（権限なし）",
                        )
                    },
                    selected = current == item.route,
                    onClick = { if (enabled) onNavigate(item.route) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
