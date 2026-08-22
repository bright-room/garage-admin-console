package net.brightroom.garage.web.screens.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.brightroom.garage.web.api.displayMessage
import net.brightroom.garage.web.session.LocalSession

/**
 * admin token を入力してログインする。
 *
 * コンソールはトークンを保持しないため、ここで入力された値がそのまま
 * 利用者の権限になる（spec §6.2）。
 *
 * e2e は role と表示テキストで要素を掴む（Compose はキャンバスに描画するため
 * testTag は DOM に露出しない）。ボタンやラベルの文言を変えるときは e2e も直すこと。
 */
@Composable
fun LoginScreen(onSignedIn: () -> Unit) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }

    fun submit() {
        if (input.isBlank() || submitting) return

        submitting = true
        error = null
        scope.launch {
            val failure = session.signIn(input.trim())
            submitting = false

            if (failure == null) {
                onSignedIn()
            } else {
                error = failure.displayMessage
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Card(modifier = Modifier.width(460.dp)) {
            Column(
                modifier = Modifier.padding(32.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Garage Admin Console", style = MaterialTheme.typography.headlineSmall)

                Text(
                    "Admin API トークンを入力してください。" +
                        "できることは、そのトークンに設定された scope の範囲に従います。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        error = null
                    },
                    label = { Text("Admin API token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    isError = error != null,
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth(),
                )

                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Button(
                    onClick = { submit() },
                    enabled = input.isNotBlank() && !submitting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (submitting) {
                        CircularProgressIndicator(modifier = Modifier.width(20.dp))
                    } else {
                        Text("ログイン")
                    }
                }
            }
        }
    }
}
