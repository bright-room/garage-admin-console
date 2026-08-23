package net.brightroom.garage.shared.navigation

/**
 * URL のクエリ値のパーセントエンコード（RFC 3986）。
 *
 * `:shared` は ktor-http を持たないため手書きする。オブジェクトの接頭辞には
 * スラッシュ・スペース・日本語が入るため、UTF-8 バイト列に対して行う。
 *
 * `+` はスペースとして扱わない。それは form-urlencoded の規則であり、
 * History API が扱う URL には当てはまらない。スペースは `%20` になる。
 */

private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
private const val HEX = "0123456789ABCDEF"

fun percentEncode(value: String): String {
    val builder = StringBuilder(value.length)

    value.encodeToByteArray().forEach { byte ->
        val code = byte.toInt() and 0xFF
        val char = code.toChar()

        if (code < 0x80 && char in UNRESERVED) {
            builder.append(char)
        } else {
            builder.append('%').append(HEX[code shr 4]).append(HEX[code and 0x0F])
        }
    }

    return builder.toString()
}

fun percentDecode(value: String): String {
    if ('%' !in value) return value

    val bytes = ArrayList<Byte>(value.length)
    var index = 0

    while (index < value.length) {
        val char = value[index]
        val high = if (char == '%') hexDigit(value.getOrNull(index + 1)) else null
        val low = if (high != null) hexDigit(value.getOrNull(index + 2)) else null

        if (high != null && low != null) {
            bytes.add(((high shl 4) or low).toByte())
            index += 3
        } else {
            // 壊れたエスケープはリテラルとして残す
            char.toString().encodeToByteArray().forEach(bytes::add)
            index++
        }
    }

    return bytes.toByteArray().decodeToString()
}

private fun hexDigit(char: Char?): Int? = when (char) {
    null -> null
    in '0'..'9' -> char - '0'
    in 'a'..'f' -> char - 'a' + 10
    in 'A'..'F' -> char - 'A' + 10
    else -> null
}
