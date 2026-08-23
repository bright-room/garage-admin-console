package net.brightroom.garage.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.garage.requireValidToken
import net.brightroom.garage.server.s3.SecretCache
import net.brightroom.garage.server.s3.hashToken
import net.brightroom.garage.shared.api.toSession

fun Route.sessionRoutes(client: GarageAdminClient, cache: SecretCache) {
    route("/session") {
        get {
            val token = client.requireValidToken(call.adminToken())

            call.respond(token.toSession())
        }

        post("/logout") {
            // トークンの検証は行わない。ログアウトは失敗しないほうが利用者に親切である。
            //
            // サーバーが持つ唯一の状態は S3 secret のキャッシュなので、それを捨てる。
            // 引けるのは同じトークンを提示できる者だけであり、これは機密性の担保
            // というより後始末である（spec §6.6）。
            cache.purge(hashToken(call.adminToken()))

            call.respond(HttpStatusCode.NoContent)
        }
    }
}
