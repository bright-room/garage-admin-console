package net.brightroom.garage.server.plugins

import aws.sdk.kotlin.services.s3.model.AccessDenied
import aws.sdk.kotlin.services.s3.model.NoSuchBucket
import aws.sdk.kotlin.services.s3.model.NoSuchKey
import aws.sdk.kotlin.services.s3.model.S3Exception
import aws.smithy.kotlin.runtime.SdkBaseException
import io.ktor.http.BadContentTypeFormatException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import net.brightroom.garage.server.api.InvalidRequestException
import net.brightroom.garage.server.api.MissingContentLengthException
import net.brightroom.garage.server.api.MissingTokenException
import net.brightroom.garage.server.api.respondProblem
import net.brightroom.garage.server.garage.GarageException
import net.brightroom.garage.server.s3.BucketNotAddressableException
import net.brightroom.garage.server.s3.NoUsableKeyException
import net.brightroom.garage.shared.api.ProblemTypes

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

        exception<InvalidRequestException> { call, cause ->
            call.respondProblem(status = HttpStatusCode.BadRequest, detail = cause.message)
        }

        // 本文のデシリアライズ失敗など、Ktor が投げる 400。内部のメッセージは外に出さない
        exception<BadRequestException> { call, _ ->
            call.respondProblem(
                status = HttpStatusCode.BadRequest,
                detail = "リクエストの内容を解釈できませんでした",
            )
        }

        // Content-Type が無い / JSON でない。クライアント起因なのでサーバー障害として扱わない
        exception<ContentTransformationException> { call, _ ->
            call.respondProblem(
                status = HttpStatusCode.UnsupportedMediaType,
                detail = "リクエストの Content-Type が application/json ではありません",
            )
        }

        // Content-Type ヘッダの値そのものがパースできない形式。クライアント起因なので 400
        exception<BadContentTypeFormatException> { call, _ ->
            call.respondProblem(
                status = HttpStatusCode.BadRequest,
                detail = "Content-Type ヘッダの形式が不正です",
            )
        }

        // S3 ブラウザだけが縮退する 2 つ。web が案内を出し分けられるよう型を付ける
        exception<NoUsableKeyException> { call, cause ->
            call.respondProblem(
                status = HttpStatusCode.Conflict,
                detail = cause.message,
                type = ProblemTypes.NO_USABLE_KEY,
                title = "利用できるアクセスキーがありません",
            )
        }

        exception<BucketNotAddressableException> { call, cause ->
            call.respondProblem(
                status = HttpStatusCode.Conflict,
                detail = cause.message,
                type = ProblemTypes.BUCKET_NOT_ADDRESSABLE,
                title = "S3 でアドレスできないバケットです",
            )
        }

        exception<MissingContentLengthException> { call, cause ->
            call.respondProblem(status = HttpStatusCode.LengthRequired, detail = cause.message)
        }

        exception<NoSuchKey> { call, _ ->
            call.respondProblem(
                status = HttpStatusCode.NotFound,
                detail = "オブジェクトが見つかりません",
            )
        }

        // Garage の判定をそのまま伝える（spec §6.3）。502 に握り潰さない
        exception<AccessDenied> { call, _ ->
            call.respondProblem(
                status = HttpStatusCode.Forbidden,
                detail = "このキーではこの操作を行えません",
            )
        }

        exception<NoSuchBucket> { call, _ ->
            call.respondProblem(status = HttpStatusCode.NotFound, detail = "バケットが見つかりません")
        }

        // S3 側の失敗。SDK のメッセージは外に出さない（資格情報や内部の詳細を含みうる）
        exception<S3Exception> { call, cause ->
            call.application.log.error("S3 request failed at ${call.request.path()}", cause)
            call.respondProblem(
                status = HttpStatusCode.BadGateway,
                detail = "ストレージへのアクセスに失敗しました",
            )
        }

        // 接続拒否・DNS 失敗・TLS エラー・タイムアウトなど、S3 まで届かなかった失敗。
        // HttpException 等は S3Exception の子孫ではなく SdkBaseException の直接の子なので
        // 別ハンドラが要る。上流の障害として 502 にする
        exception<SdkBaseException> { call, cause ->
            call.application.log.error("S3 transport failed at ${call.request.path()}", cause)
            call.respondProblem(
                status = HttpStatusCode.BadGateway,
                detail = "ストレージへのアクセスに失敗しました",
            )
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
