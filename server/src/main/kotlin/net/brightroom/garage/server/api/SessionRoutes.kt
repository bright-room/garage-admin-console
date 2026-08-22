package net.brightroom.garage.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.garage.garageBody
import net.brightroom.garage.shared.api.toSession
import net.brightroom.garage.shared.model.garage.AdminToken

private const val CURRENT_TOKEN_INFO = "GetCurrentAdminTokenInfo"

fun Route.sessionRoutes(client: GarageAdminClient) {
    route("/session") {

        get {
            val token = call.adminToken()
            val info: AdminToken = client.get(token, CURRENT_TOKEN_INFO)
                .garageBody(CURRENT_TOKEN_INFO)

            call.respond(info.toSession())
        }

        post("/logout") {
            // トークンの検証は行わない。ログアウトは失敗しないほうが利用者に親切であり、
            // サーバーはトークンを保持していないため破棄すべき状態も無い。
            //
            // Phase 2 で S3 secret のキャッシュを導入したら、ここでそのトークン
            // ハッシュ配下のエントリを purge する。
            call.adminToken()
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
