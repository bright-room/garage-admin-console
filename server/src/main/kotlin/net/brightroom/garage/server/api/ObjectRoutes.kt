package net.brightroom.garage.server.api

import aws.smithy.kotlin.runtime.content.writeToOutputStream
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.garage.inspectObject
import net.brightroom.garage.server.s3.S3CredentialResolver
import net.brightroom.garage.server.s3.S3ObjectStore

/**
 * オブジェクトのルート。
 *
 * 一覧・転送・削除は S3 API を、詳細は Garage の `InspectObject` を使う。
 * どちらもブラウザからは `/api/buckets/{id}/objects` 配下として見える。
 *
 * 本文はメモリに載せずに流す。アップロードは `Content-Length` を要求する
 * （S3 の PutObject が長さを求めるため）。
 */
fun Route.objectRoutes(client: GarageAdminClient, resolver: S3CredentialResolver, store: S3ObjectStore) {
    route("/buckets/{id}/objects") {
        get {
            val token = call.adminToken()
            val credentials = resolver.resolve(token, call.pathParam("id"))

            call.respond(
                store.list(
                    credentials = credentials,
                    prefix = call.request.queryParameters["prefix"].orEmpty(),
                    continuationToken = call.request.queryParameters["token"],
                ),
            )
        }

        put {
            val token = call.adminToken()
            val key = call.queryParam("key")
            val contentType = call.request.contentType().orOctetStreamIfAny()
            // Content-Length を先に確かめる。無ければ資格情報の解決すら試みない
            val contentLength = call.request.contentLength() ?: throw MissingContentLengthException()
            val credentials = resolver.resolve(token, call.pathParam("id"))

            store.put(
                credentials = credentials,
                key = key,
                contentType = contentType,
                contentLength = contentLength,
                stream = call.receiveStream(),
            )

            call.respond(HttpStatusCode.NoContent)
        }

        delete {
            val token = call.adminToken()
            val key = call.queryParam("key")
            val credentials = resolver.resolve(token, call.pathParam("id"))

            store.delete(credentials, key)

            call.respond(HttpStatusCode.NoContent)
        }

        get("/content") {
            val token = call.adminToken()
            val key = call.queryParam("key")
            val credentials = resolver.resolve(token, call.pathParam("id"))

            // Content-Disposition は付けない。ファイル名はブラウザ側が決める（P2-2）
            store.download(credentials, key) { contentType, body ->
                call.respondOutputStream(contentType = ContentType.parse(contentType)) {
                    body.writeToOutputStream(this)
                }
            }
        }

        get("/inspect") {
            call.respond(
                client.inspectObject(call.adminToken(), call.pathParam("id"), call.queryParam("key")),
            )
        }
    }
}

/**
 * `Content-Type` ヘッダが無いと Ktor はワイルドカード（[ContentType.Any]）を返す。
 * S3 に渡す型としては無意味なので、その場合だけ既定にフォールバックする。
 */
internal fun ContentType.orOctetStreamIfAny(): String =
    if (this == ContentType.Any) "application/octet-stream" else toString()
