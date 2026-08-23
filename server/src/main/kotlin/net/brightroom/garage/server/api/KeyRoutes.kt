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
import net.brightroom.garage.server.garage.createKey
import net.brightroom.garage.server.garage.deleteKey
import net.brightroom.garage.server.garage.getKeyInfo
import net.brightroom.garage.server.garage.importKey
import net.brightroom.garage.server.garage.listKeys
import net.brightroom.garage.server.garage.updateKey
import net.brightroom.garage.shared.api.CreateKeyRequest
import net.brightroom.garage.shared.api.ImportKeyRequest
import net.brightroom.garage.shared.api.UpdateKeyRequest

/**
 * アクセスキーのルート。
 *
 * secret access key は `?showSecret=true` を明示したときだけ取得する（P2-7）。
 * サーバーはその値をキャッシュにもログにも残さず、応答としてのみ返す。
 */
fun Route.keyRoutes(client: GarageAdminClient) {
    route("/keys") {
        get {
            call.respond(client.listKeys(call.adminToken()))
        }

        post {
            val request = call.receive<CreateKeyRequest>()
            call.respond(client.createKey(call.adminToken(), request))
        }

        post("/import") {
            val request = call.receive<ImportKeyRequest>()
            call.respond(client.importKey(call.adminToken(), request))
        }

        get("/{id}") {
            val showSecret = call.request.queryParameters["showSecret"] == "true"
            call.respond(client.getKeyInfo(call.adminToken(), call.pathParam("id"), showSecret))
        }

        patch("/{id}") {
            val request = call.receive<UpdateKeyRequest>()
            call.respond(client.updateKey(call.adminToken(), call.pathParam("id"), request))
        }

        delete("/{id}") {
            client.deleteKey(call.adminToken(), call.pathParam("id"))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
