package net.brightroom.garage.web

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import net.brightroom.garage.web.router.rememberRouter
import net.brightroom.garage.web.theme.GarageAdminTheme

@Composable
fun App() {
    val router = rememberRouter()

    GarageAdminTheme {
        Text("route: ${router.current}")
    }
}
