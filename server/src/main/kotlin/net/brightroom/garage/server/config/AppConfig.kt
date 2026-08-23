package net.brightroom.garage.server.config

import kotlinx.serialization.Serializable

/**
 * サーバーの設定。`application.yaml` の `garage` セクションをそのまま受ける。
 *
 * admin token は含まない。トークンは利用者がブラウザで入力し、
 * リクエストごとに転送されるため、サーバーは保持しない。
 *
 * S3 の資格情報も含まない。admin token から導出する（spec §6.4）。
 * ここにあるのは接続先の情報だけである。
 */
@Serializable
data class AppConfig(val admin: Admin, val s3: S3) {
    @Serializable
    data class Admin(val endpoint: String)

    /**
     * S3 API の接続先。
     *
     * spec §9 のポータブルな側であり、Garage 固有の値を前提にしない。
     * [pathStyle] は仮想ホスト形式（`bucket.example.com`）を使わないことを指す。
     */
    @Serializable
    data class S3(val endpoint: String, val region: String, val pathStyle: Boolean)
}
