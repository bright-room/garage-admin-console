package net.brightroom.garage.server.plugins

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.request.path
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import net.brightroom.garage.server.api.respondProblem
import net.brightroom.garage.shared.api.ProblemDetails
import net.brightroom.garage.shared.api.of

/**
 * wasm の成果物を配信する。
 *
 * クライアント側は History API でルーティングするため、未知のパスは index.html に
 * フォールバックさせる必要がある。ただし `/api` 配下は API のエラーが HTML に
 * なってしまうため、明示的に JSON の 404 を返す。
 */
fun Application.configureStaticFiles() {
    routing {
        route("/api/{...}") {
            get {
                call.respondProblem(
                    ProblemDetails.of(
                        status = HttpStatusCode.NotFound,
                        detail = "エンドポイントが見つかりません",
                        instance = call.request.path(),
                    ),
                )
            }
        }

        singlePageApplication {
            useResources = true
            filesPath = "web"
            defaultPage = "index.html"
        }
    }
}
