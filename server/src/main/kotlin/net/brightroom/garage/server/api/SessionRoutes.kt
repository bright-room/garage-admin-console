package net.brightroom.garage.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.garage.requireValidToken
import net.brightroom.garage.shared.api.toSession

fun Route.sessionRoutes(client: GarageAdminClient) {
    route("/session") {

        get {
            val token = client.requireValidToken(call.adminToken())

            call.respond(token.toSession())
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
