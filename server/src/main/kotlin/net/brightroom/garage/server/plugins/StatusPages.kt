package net.brightroom.garage.server.plugins

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import net.brightroom.garage.server.api.MissingTokenException
import net.brightroom.garage.server.api.respondProblem
import net.brightroom.garage.server.garage.GarageException
import net.brightroom.garage.shared.api.ProblemDetails
import net.brightroom.garage.shared.api.of

/**
 * すべてのエラーを RFC 9457 の problem details に正規化する。
 *
 * Garage のエラー形をそのままブラウザへ漏らさないことが目的。
 */
fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<GarageException> { call, cause ->
            call.respondProblem(
                ProblemDetails.of(
                    status = cause.status,
                    detail = cause.message,
                    instance = call.request.path(),
                    operation = cause.operation,
                ),
            )
        }

        exception<MissingTokenException> { call, cause ->
            call.respondProblem(
                ProblemDetails.of(
                    status = HttpStatusCode.Unauthorized,
                    detail = cause.message,
                    instance = call.request.path(),
                ),
            )
        }

        exception<Throwable> { call, cause ->
            // 例外そのものは記録するが、リクエストヘッダは触らない。
            // detail に内部の例外メッセージを載せないこと（外に出す情報を絞る）。
            call.application.log.error("Unhandled exception at ${call.request.path()}", cause)
            call.respondProblem(
                ProblemDetails.of(
                    status = HttpStatusCode.InternalServerError,
                    detail = "サーバー内部でエラーが発生しました",
                    instance = call.request.path(),
                ),
            )
        }

        status(HttpStatusCode.NotFound) { call, status ->
            // 静的ファイルのフォールバックは Task 11 で /api 以外を index.html に流すため、
            // ここへ来るのは実質 /api 配下の未定義パスだけになる
            call.respondProblem(
                ProblemDetails.of(status = status, instance = call.request.path()),
            )
        }
    }
}
