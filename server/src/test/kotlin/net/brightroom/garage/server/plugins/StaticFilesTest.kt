package net.brightroom.garage.server.plugins

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import net.brightroom.garage.shared.api.ProblemDetails
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StaticFilesTest {

    private fun io.ktor.server.testing.ApplicationTestBuilder.staticApp() {
        application {
            configureSerialization()
            configureStatusPages()
            configureStaticFiles()
        }
    }

    @Test
    fun servesIndexAtRoot() = testApplication {
        staticApp()

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("ComposeTarget"))
    }

    @Test
    fun fallsBackToIndexForClientRoutes() = testApplication {
        staticApp()

        val response = client.get("/buckets/abc-123")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(
            response.bodyAsText().contains("ComposeTarget"),
            "クライアント側ルートへの直接アクセスは index.html を返すべき",
        )
    }

    @Test
    fun doesNotFallBackForApiPaths() = testApplication {
        staticApp()

        val response = client.get("/api/does-not-exist")

        assertEquals(HttpStatusCode.NotFound, response.status)
        val problem: ProblemDetails = GarageJson.decodeFromString(response.bodyAsText())
        assertEquals(HttpStatusCode.NotFound.value, problem.status)
    }
}
