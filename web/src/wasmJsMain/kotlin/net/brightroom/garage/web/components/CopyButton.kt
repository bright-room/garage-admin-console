package net.brightroom.garage.web.components

import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CopyButton(
    text: String,
    label: String = "Copy",
) {
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    TextButton(onClick = {
        window.navigator.clipboard.writeText(text)
        copied = true
        scope.launch {
            delay(2000)
            copied = false
        }
    }) {
        Text(if (copied) "Copied!" else label)
    }
}
