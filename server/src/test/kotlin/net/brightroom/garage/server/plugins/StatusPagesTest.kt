package net.brightroom.garage.server.plugins

import aws.sdk.kotlin.services.s3.model.AccessDenied
import aws.sdk.kotlin.services.s3.model.NoSuchBucket
import aws.sdk.kotlin.services.s3.model.NoSuchKey
import aws.sdk.kotlin.services.s3.model.S3Exception
import aws.smithy.kotlin.runtime.http.HttpErrorCode
import aws.smithy.kotlin.runtime.http.HttpException
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.BadContentTypeFormatException
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import net.brightroom.garage.server.api.MissingTokenException
import net.brightroom.garage.server.garage.GarageException
import net.brightroom.garage.shared.api.ProblemDetails
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class StatusPagesTest {

    private fun problemOf(body: String): ProblemDetails = GarageJson.decodeFromString(body)

    @Test
    fun normalisesForbiddenFromGarage() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                get("/boom") {
                    throw GarageException(
                        status = HttpStatusCode.Forbidden,
                        operation = "GetKeyInfo",
                        message = "insufficient scope",
                    )
                }
            }
        }

        val response = client.get("/boom")

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val problem = problemOf(response.bodyAsText())
        assertEquals(HttpStatusCode.Forbidden.value, problem.status)
        assertEquals("Forbidden", problem.title)
        assertEquals("insufficient scope", problem.detail)
        assertEquals("GetKeyInfo", problem.operation)
        assertEquals("/boom", problem.instance)
    }

    @Test
    fun usesProblemJsonMediaType() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                get("/boom") { throw MissingTokenException() }
            }
        }

        val response = client.get("/boom")

        // RFC 9457 の定める media type
        assertEquals("application/problem+json", response.contentType()?.withoutParameters()?.toString())
    }

    @Test
    fun missingTokenBecomesUnauthorized() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                get("/boom") { throw MissingTokenException() }
            }
        }

        val response = client.get("/boom")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(HttpStatusCode.Unauthorized.value, problemOf(response.bodyAsText()).status)
    }

    @Test
    fun unexpectedExceptionBecomesInternalError() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                get("/boom") { throw IllegalStateException("unexpected") }
            }
        }

        val response = client.get("/boom")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val problem = problemOf(response.bodyAsText())
        assertEquals(HttpStatusCode.InternalServerError.value, problem.status)
        assertEquals("Internal Server Error", problem.title)
        // 内部エラーの詳細は外に出さない
        assertEquals(null, problem.operation)
    }

    @Test
    fun noSuchKeyBecomesNotFound() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                get("/boom") { throw NoSuchKey { } }
            }
        }

        val response = client.get("/boom")

        assertEquals(HttpStatusCode.NotFound, response.status)
        val problem = problemOf(response.bodyAsText())
        assertEquals(HttpStatusCode.NotFound.value, problem.status)
        assertEquals("オブジェクトが見つかりません", problem.detail)
    }

    @Test
    fun accessDeniedBecomesForbidden() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                get("/boom") { throw AccessDenied { } }
            }
        }

        val response = client.get("/boom")

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val problem = problemOf(response.bodyAsText())
        assertEquals(HttpStatusCode.Forbidden.value, problem.status)
        assertEquals("このキーではこの操作を行えません", problem.detail)
    }

    @Test
    fun noSuchBucketBecomesNotFound() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                get("/boom") { throw NoSuchBucket { } }
            }
        }

        val response = client.get("/boom")

        assertEquals(HttpStatusCode.NotFound, response.status)
        val problem = problemOf(response.bodyAsText())
        assertEquals(HttpStatusCode.NotFound.value, problem.status)
        assertEquals("バケットが見つかりません", problem.detail)
    }

    @Test
    fun sdkTransportFailureBecomesBadGateway() = testApplication {
        // HttpException は S3Exception の子孫ではなく SdkBaseException の直接の子。
        // 接続拒否・DNS 失敗などを模す
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                get("/boom") { throw HttpException("connection refused", HttpErrorCode.SDK_UNKNOWN, false) }
            }
        }

        val response = client.get("/boom")

        assertEquals(HttpStatusCode.BadGateway, response.status)
        val problem = problemOf(response.bodyAsText())
        assertEquals(HttpStatusCode.BadGateway.value, problem.status)
        assertEquals("ストレージへのアクセスに失敗しました", problem.detail)
    }

    @Test
    fun otherS3ExceptionBecomesBadGatewayWithoutLeakingSdkMessage() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                // SDK のメッセージには、資格情報や内部の詳細が含まれ得るという想定で
                // 「絶対に外に出てはならない」目印の文字列を混ぜる
                get("/boom") { throw S3Exception("secret-looking-detail-do-not-leak-9f3a") }
            }
        }

        val response = client.get("/boom")

        assertEquals(HttpStatusCode.BadGateway, response.status)
        val body = response.bodyAsText()
        val problem = problemOf(body)
        assertEquals(HttpStatusCode.BadGateway.value, problem.status)
        assertEquals("ストレージへのアクセスに失敗しました", problem.detail)
        assertFalse(body.contains("secret-looking-detail-do-not-leak-9f3a"))
    }

    @Test
    fun badContentTypeFormatBecomesBadRequest() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                get("/boom") { throw BadContentTypeFormatException("not-a-content-type;;;") }
            }
        }

        val response = client.get("/boom")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(HttpStatusCode.BadRequest.value, problemOf(response.bodyAsText()).status)
    }

    @Test
    fun unknownApiPathReturnsNormalisedNotFound() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                get("/ok") { call.respondText("ok") }
            }
        }

        val response = client.get("/api/does-not-exist")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(HttpStatusCode.NotFound.value, problemOf(response.bodyAsText()).status)
    }

    @Test
    fun omitsTypeMemberSoItDefaultsToAboutBlank() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                get("/boom") { throw MissingTokenException() }
            }
        }

        val body = client.get("/boom").bodyAsText()

        assertEquals(false, body.contains("\"type\""))
    }
}
