package net.brightroom.garage.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.garage.addBucketAlias
import net.brightroom.garage.server.garage.allowBucketKey
import net.brightroom.garage.server.garage.cleanupIncompleteUploads
import net.brightroom.garage.server.garage.createBucket
import net.brightroom.garage.server.garage.deleteBucket
import net.brightroom.garage.server.garage.denyBucketKey
import net.brightroom.garage.server.garage.getBucketInfo
import net.brightroom.garage.server.garage.listBuckets
import net.brightroom.garage.server.garage.removeBucketAlias
import net.brightroom.garage.server.garage.updateBucket
import net.brightroom.garage.shared.api.BucketAliasRequest
import net.brightroom.garage.shared.api.BucketKeyPermissionRequest
import net.brightroom.garage.shared.api.CleanupUploadsRequest
import net.brightroom.garage.shared.api.CleanupUploadsResult
import net.brightroom.garage.shared.api.CreateBucketRequest
import net.brightroom.garage.shared.api.UpdateBucketRequest
import net.brightroom.garage.shared.model.garage.BucketKeyPermissions

/**
 * バケットのルート。Garage の operation 名は外に出さない（spec §7）。
 *
 * scope の判定はしない。可否は Garage が返す 403 で決まる（spec §6.3）。
 */
fun Route.bucketRoutes(client: GarageAdminClient) {
    route("/buckets") {
        get {
            call.respond(client.listBuckets(call.adminToken()))
        }

        post {
            val request = call.receive<CreateBucketRequest>()
            call.respond(client.createBucket(call.adminToken(), request))
        }

        route("/{id}") {
            get {
                call.respond(client.getBucketInfo(call.adminToken(), call.pathParam("id")))
            }

            patch {
                val request = call.receive<UpdateBucketRequest>()
                call.respond(client.updateBucket(call.adminToken(), call.pathParam("id"), request))
            }

            delete {
                client.deleteBucket(call.adminToken(), call.pathParam("id"))
                call.respond(HttpStatusCode.NoContent)
            }

            post("/aliases") {
                val request = call.receive<BucketAliasRequest>()
                call.respond(
                    client.addBucketAlias(call.adminToken(), call.pathParam("id"), request.alias),
                )
            }

            delete("/aliases") {
                // DELETE に本文は載せない（P2-12）
                call.respond(
                    client.removeBucketAlias(
                        call.adminToken(),
                        call.pathParam("id"),
                        call.queryParam("alias"),
                    ),
                )
            }

            put("/keys/{keyId}") {
                val request = call.receive<BucketKeyPermissionRequest>()
                call.respond(
                    client.allowBucketKey(
                        call.adminToken(),
                        call.pathParam("id"),
                        call.pathParam("keyId"),
                        request.permissions,
                    ),
                )
            }

            delete("/keys/{keyId}") {
                // 部分的な剥奪はしない。減らしたいときは外してから付け直す（P2-11）
                call.respond(
                    client.denyBucketKey(
                        call.adminToken(),
                        call.pathParam("id"),
                        call.pathParam("keyId"),
                        BucketKeyPermissions(owner = true, read = true, write = true),
                    ),
                )
            }

            post("/cleanup-uploads") {
                val request = call.receive<CleanupUploadsRequest>()
                val deleted = client.cleanupIncompleteUploads(
                    call.adminToken(),
                    call.pathParam("id"),
                    request.olderThanSecs,
                )
                call.respond(CleanupUploadsResult(deleted))
            }
        }
    }
}
