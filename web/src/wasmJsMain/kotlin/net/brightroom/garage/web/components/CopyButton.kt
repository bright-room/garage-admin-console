@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package net.brightroom.garage.web.components

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CopyButton(
    text: String,
    label: String = "Copy",
    modifier: Modifier = Modifier,
) {
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    TextButton(
        onClick = {
            window.navigator.clipboard.writeText(text)
            copied = true
            scope.launch {
                delay(2000)
                copied = false
            }
        },
        modifier = modifier,
    ) {
        Text(if (copied) "Copied!" else label)
    }
}
