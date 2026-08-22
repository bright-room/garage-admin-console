package net.brightroom.garage.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/**
 * wasm の成果物を配信する。
 *
 * クライアント側は History API でルーティングするため、未知のパスは index.html に
 * フォールバックさせる必要がある。ただし `/api` 配下まで index.html を返すと
 * API のエラーが HTML になってしまうため、ここで打ち切って StatusPages に渡す。
 * レスポンスの形を組み立てるのは StatusPages の仕事である。
 */
fun Application.configureStaticFiles() {
    routing {
        route("/api/{...}") {
            get { throw NotFoundException("エンドポイントが見つかりません") }
        }

        singlePageApplication {
            useResources = true
            filesPath = "web"
            defaultPage = "index.html"
        }
    }
}
