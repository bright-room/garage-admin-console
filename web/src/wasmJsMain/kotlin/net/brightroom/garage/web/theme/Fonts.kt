package net.brightroom.garage.web.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes

private const val FONT_PATH = "/fonts/NotoSansJP-Regular.ttf"

/**
 * 日本語グリフを持つフォントを読み込む。
 *
 * Compose for Web は Skia でキャンバスに描画するため、ブラウザや OS のフォントを
 * 使わない。埋め込みの既定フォントに日本語が含まれないため、同梱したフォントを
 * 明示的に読み込まないと UI の日本語がすべて豆腐（□）になる。
 *
 * 読み込みに失敗した場合は null を返す。フォントが無くてもコンソールは
 * 起動すべきであり、英数字は既定フォントで読める。
 */
suspend fun loadJapaneseFontFamily(): FontFamily? =
    runCatching {
        val bytes = HttpClient(Js).get(FONT_PATH).readRawBytes()
        FontFamily(Font(identity = "NotoSansJP", data = bytes))
    }.getOrNull()
