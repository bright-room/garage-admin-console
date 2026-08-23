package net.brightroom.garage.shared.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

class PercentEncodingTest {

    @Test
    fun leavesUnreservedCharactersAlone() {
        assertEquals("abcXYZ-09_.~", percentEncode("abcXYZ-09_.~"))
    }

    @Test
    fun encodesReservedCharacters() {
        assertEquals("logs%2F2026%2F", percentEncode("logs/2026/"))
        assertEquals("a%20b", percentEncode("a b"))
        assertEquals("a%2Bb%26c%3Dd", percentEncode("a+b&c=d"))
        assertEquals("100%25", percentEncode("100%"))
    }

    @Test
    fun encodesMultibyteAsUtf8() {
        // 「あ」は U+3042 → UTF-8 で E3 81 82
        assertEquals("%E3%81%82", percentEncode("あ"))
    }

    @Test
    fun roundTripsEveryKindOfValue() {
        listOf(
            "",
            "logs/2026/",
            "日本語 フォルダ/",
            "a+b&c=d",
            "100%",
            "emoji-🗄️/",
        ).forEach { value ->
            assertEquals(value, percentDecode(percentEncode(value)))
        }
    }

    @Test
    fun decodesLowercaseHex() {
        assertEquals("あ", percentDecode("%e3%81%82"))
    }

    @Test
    fun keepsPlusAsIs() {
        // form-urlencoded ではないので + はスペースにしない
        assertEquals("a+b", percentDecode("a+b"))
    }

    @Test
    fun leavesBrokenEscapesLiteral() {
        // URL は外部入力である。壊れていても例外を投げず、読めるものだけ読む
        assertEquals("%zz", percentDecode("%zz"))
        assertEquals("ab%", percentDecode("ab%"))
    }
}
