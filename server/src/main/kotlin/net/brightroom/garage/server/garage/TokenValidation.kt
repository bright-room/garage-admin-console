package net.brightroom.garage.server.garage

import io.ktor.http.HttpStatusCode
import net.brightroom.garage.shared.model.garage.AdminToken

/** すべての admin token が呼べる operation。トークンの有効性の確認に使う。 */
const val CURRENT_TOKEN_INFO: String = "GetCurrentAdminTokenInfo"

/**
 * トークンが有効かを Garage に確認し、その情報を返す。
 *
 * Garage v2.3.0 は無効な bearer token に対して 401 ではなく 403 を返し、
 * その本文は scope 不足の 403 と区別できない。一方 [CURRENT_TOKEN_INFO] は
 * scope を限定したトークンでも常に許可される（実機で確認済み）。
 * したがってこの operation に対する 403 は「トークンが無効」を意味する。
 *
 * ここで 401 に正規化することで、クライアントは 401 を見てログイン画面へ戻せる。
 */
suspend fun GarageAdminClient.requireValidToken(token: String): AdminToken = try {
    get(token, CURRENT_TOKEN_INFO).garageBody<AdminToken>(CURRENT_TOKEN_INFO)
} catch (e: GarageException) {
    if (e.status == HttpStatusCode.Forbidden) {
        throw GarageException(
            status = HttpStatusCode.Unauthorized,
            operation = CURRENT_TOKEN_INFO,
            message = e.message,
        )
    }
    throw e
}
