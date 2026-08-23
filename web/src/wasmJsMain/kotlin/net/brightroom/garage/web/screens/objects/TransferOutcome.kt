@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package net.brightroom.garage.web.screens.objects

import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * ファイルの転送結果。
 *
 * 転送は JS 側の `fetch` で完結するため（P2-2）、`ApiClient` の判断を通らない。
 * 401 / 403 の扱いを取りこぼさないよう、ステータスをそのまま持ち帰る。
 */
sealed interface TransferOutcome {
    data class Done(val fileName: String) : TransferOutcome
    data object Cancelled : TransferOutcome
    data class Failed(val status: Int, val body: String) : TransferOutcome
}

/**
 * ファイルを選ばせて `PUT` する。
 *
 * `[url]` の末尾に、選ばれたファイル名をエンコードして繋ぐ。ファイル本体は
 * `fetch` の body に渡すため、ブラウザが `Content-Length` を付けてそのまま流す。
 *
 * ダイアログを閉じただけのときは `cancel` イベントで区別する（`change` は発火しない）。
 *
 * 結果は `"<status> <body>"`。キャンセルは `"cancelled "` を返す。
 */
@JsFun(
    """
    (url, token) => new Promise((resolve) => {
        const input = document.createElement('input');
        input.type = 'file';

        input.addEventListener('cancel', () => resolve('cancelled '));

        input.addEventListener('change', () => {
            const file = input.files && input.files[0];
            if (!file) {
                resolve('cancelled ');
                return;
            }

            fetch(url + encodeURIComponent(file.name), {
                method: 'PUT',
                headers: {
                    'Authorization': 'Bearer ' + token,
                    'Content-Type': file.type || 'application/octet-stream',
                },
                body: file,
            }).then((response) => {
                if (response.ok) {
                    resolve('204 ' + file.name);
                    return;
                }
                return response.text().then((body) => resolve(response.status + ' ' + body));
            }).catch((error) => resolve('0 ' + error));
        });

        input.click();
    })
    """,
)
private external fun pickAndUpload(url: String, token: String): Promise<JsString>

/**
 * `GET` した本文をファイルとして保存させる。
 *
 * ブラウザに保存させるのは Blob からの object URL である。サーバーは
 * `Content-Disposition` を付けないため、ファイル名はここで決める。
 */
@JsFun(
    """
    (url, token, fileName) => new Promise((resolve) => {
        fetch(url, { headers: { 'Authorization': 'Bearer ' + token } })
            .then((response) => {
                if (!response.ok) {
                    return response.text().then((body) => resolve(response.status + ' ' + body));
                }
                return response.blob().then((blob) => {
                    const objectUrl = URL.createObjectURL(blob);
                    const anchor = document.createElement('a');
                    anchor.href = objectUrl;
                    anchor.download = fileName;
                    anchor.click();
                    URL.revokeObjectURL(objectUrl);
                    resolve('200 ' + fileName);
                });
            })
            .catch((error) => resolve('0 ' + error));
    })
    """,
)
private external fun fetchAndSave(url: String, token: String, fileName: String): Promise<JsString>

suspend fun uploadObject(url: String, token: String): TransferOutcome =
    pickAndUpload(url, token).await().toString().toOutcome()

suspend fun downloadObject(url: String, token: String, fileName: String): TransferOutcome =
    fetchAndSave(url, token, fileName).await().toString().toOutcome()

/** `"<status> <body>"` を読み解く。`0` はネットワーク側の失敗。 */
private fun String.toOutcome(): TransferOutcome {
    if (startsWith("cancelled")) return TransferOutcome.Cancelled

    val status = substringBefore(' ').toIntOrNull() ?: 0
    val body = substringAfter(' ', "")

    return if (status in 200..299) TransferOutcome.Done(body) else TransferOutcome.Failed(status, body)
}
