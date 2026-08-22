package net.brightroom.garage.shared.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ProblemDetailsTest {

    // explicitNulls = false はサーバーと web の実際の設定に合わせている。
    // 省略可能なメンバーを null で出力しないことが RFC 9457 の形として自然。
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun encodesStatusAsNumberAndOmitsAbsentMembers() {
        val problem = ProblemDetails(
            title = "Forbidden",
            status = 403,
            detail = "insufficient scope",
            instance = "/api/overview",
            operation = "GetKeyInfo",
        )

        assertEquals(
            """{"title":"Forbidden","status":403,"detail":"insufficient scope",""" +
                """"instance":"/api/overview","operation":"GetKeyInfo"}""",
            json.encodeToString(problem),
        )
    }

    @Test
    fun omitsTypeSoItDefaultsToAboutBlank() {
        val encoded = json.encodeToString(ProblemDetails(title = "Not Found", status = 404))

        assertEquals("""{"title":"Not Found","status":404}""", encoded)
    }

    @Test
    fun roundTripsThroughJson() {
        val problem = ProblemDetails(
            title = "Bad Gateway",
            status = 502,
            detail = "upstream failed",
            operation = "GetClusterStatus",
        )

        val decoded = json.decodeFromString<ProblemDetails>(json.encodeToString(problem))

        assertEquals(problem, decoded)
        assertEquals(502, decoded.status)
    }

    @Test
    fun decodesUnknownStatusCodes() {
        val decoded = json.decodeFromString<ProblemDetails>(
            """{"title":"Weird","status":499}""",
        )

        assertEquals(499, decoded.status)
    }
}
