package net.brightroom.garage.server.plugins

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallLoggingTest {

    private val secretToken = "super-secret-admin-token-value"
    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var rootLogger: Logger

    @BeforeTest
    fun attachAppender() {
        rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        rootLogger.addAppender(appender)
    }

    @AfterTest
    fun detachAppender() {
        rootLogger.detachAppender(appender)
    }

    @Test
    fun doesNotLogAuthorizationHeader() = testApplication {
        application {
            configureCallLogging()
            routing {
                get("/api/session") { call.respondText("ok") }
            }
        }

        client.get("/api/session") {
            header(HttpHeaders.Authorization, "Bearer $secretToken")
        }

        val logged = appender.list.joinToString("\n") { it.formattedMessage }

        assertFalse(logged.contains(secretToken), "ログにトークンが含まれてはならない: $logged")
        assertFalse(logged.contains("Authorization", ignoreCase = true))
        assertTrue(logged.contains("/api/session"), "パスは記録されるべき: $logged")
    }
}
