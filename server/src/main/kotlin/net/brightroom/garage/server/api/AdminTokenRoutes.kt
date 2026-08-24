package net.brightroom.garage.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.garage.createAdminToken
import net.brightroom.garage.server.garage.deleteAdminToken
import net.brightroom.garage.server.garage.getAdminToken
import net.brightroom.garage.server.garage.listAdminTokens
import net.brightroom.garage.server.garage.updateAdminToken
import net.brightroom.garage.shared.api.CreateAdminTokenRequest
import net.brightroom.garage.shared.api.UpdateAdminTokenRequest

/**
 * Admin token のルート。
 *
 * 一覧には設定ファイル由来のトークン（`id` が null）も含まれる。それらは
 * `UpdateAdminToken` / `DeleteAdminToken` の対象にできないが、隠すと「一覧に
 * 出ないトークンがある」という嘘になるため、そのまま返す（P3-6）。
 */
fun Route.adminTokenRoutes(client: GarageAdminClient) {
    route("/admin-tokens") {
        get {
            call.respond(client.listAdminTokens(call.adminToken()))
        }

        post {
            val token = call.adminToken()
            val request = call.receive<CreateAdminTokenRequest>()

            if (request.name.isBlank()) throw InvalidRequestException("トークン名が空です")
            if (request.scope.isEmpty()) throw InvalidRequestException("scope を 1 つ以上指定してください")

            call.respond(client.createAdminToken(token, request))
        }

        get("/{id}") {
            call.respond(client.getAdminToken(call.adminToken(), call.pathParam("id")))
        }

        patch("/{id}") {
            val token = call.adminToken()
            val request = call.receive<UpdateAdminTokenRequest>()

            call.respond(client.updateAdminToken(token, call.pathParam("id"), request))
        }

        delete("/{id}") {
            client.deleteAdminToken(call.adminToken(), call.pathParam("id"))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
