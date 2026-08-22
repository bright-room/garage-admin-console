package net.brightroom.garage.server.plugins

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import net.brightroom.garage.server.api.MissingTokenException
import net.brightroom.garage.server.api.respondProblem
import net.brightroom.garage.server.garage.GarageException

/**
 * すべてのエラーを RFC 9457 の problem details に正規化する。
 *
 * エラーレスポンスの形を組み立てるのはここだけ。Garage のエラー形を
 * そのままブラウザへ漏らさないことが目的。
 */
fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<GarageException> { call, cause ->
            call.respondProblem(
                status = cause.status,
                detail = cause.message,
                operation = cause.operation,
            )
        }

        exception<MissingTokenException> { call, cause ->
            call.respondProblem(status = HttpStatusCode.Unauthorized, detail = cause.message)
        }

        exception<NotFoundException> { call, cause ->
            call.respondProblem(status = HttpStatusCode.NotFound, detail = cause.message)
        }

        exception<Throwable> { call, cause ->
            // 例外そのものは記録するが、リクエストヘッダは触らない。
            // detail に内部の例外メッセージを載せないこと（外に出す情報を絞る）。
            call.application.log.error("Unhandled exception at ${call.request.path()}", cause)
            call.respondProblem(
                status = HttpStatusCode.InternalServerError,
                detail = "サーバー内部でエラーが発生しました",
            )
        }

        status(HttpStatusCode.NotFound) { call, status ->
            call.respondProblem(status = status)
        }
    }
}
