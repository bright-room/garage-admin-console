package net.brightroom.garage.shared.api

import io.ktor.http.HttpStatusCode
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
        val problem = ProblemDetails.of(
            status = HttpStatusCode.Forbidden,
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
    fun titleDefaultsToTheStatusReasonPhrase() {
        // type を省略した場合、RFC 9457 は about:blank とみなし、
        // title はその status の推奨理由句であるべきと定めている
        assertEquals("Not Found", ProblemDetails.of(HttpStatusCode.NotFound).title)
        assertEquals("Unauthorized", ProblemDetails.of(HttpStatusCode.Unauthorized).title)
        assertEquals(
            "Internal Server Error",
            ProblemDetails.of(HttpStatusCode.InternalServerError).title,
        )
    }

    @Test
    fun omitsTypeSoItDefaultsToAboutBlank() {
        val encoded = json.encodeToString(ProblemDetails.of(HttpStatusCode.NotFound))

        assertEquals("""{"title":"Not Found","status":404}""", encoded)
    }

    @Test
    fun roundTripsThroughJson() {
        val problem = ProblemDetails.of(
            status = HttpStatusCode.BadGateway,
            detail = "upstream failed",
            operation = "GetClusterStatus",
        )

        val decoded = json.decodeFromString<ProblemDetails>(json.encodeToString(problem))

        assertEquals(problem, decoded)
        assertEquals(HttpStatusCode.BadGateway, decoded.status)
        assertEquals(502, decoded.status.value)
    }

    @Test
    fun decodesUnknownStatusCodes() {
        val decoded = json.decodeFromString<ProblemDetails>(
            """{"title":"Weird","status":499}""",
        )

        assertEquals(499, decoded.status.value)
    }
}
