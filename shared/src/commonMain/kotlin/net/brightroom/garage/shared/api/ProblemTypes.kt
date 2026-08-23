package net.brightroom.garage.shared.api

/**
 * コンソール固有の問題型（RFC 9457 の `type`）。
 *
 * spec §7.1 は「コンソール固有の問題型を定義する必要が生じた時点で `type` に
 * URI を入れる」と定めている。S3 ブラウザの 2 つの縮退は、どちらも HTTP
 * ステータスだけでは区別できず、画面に出す案内も導線も異なるため、ここで型を持つ。
 *
 * 解決可能な URL を用意する予定は無いため URN を使う。RFC 9457 は type が
 * 解決できることを求めていない。
 *
 * `type` を持つ problem では `title` は「その status の推奨理由句」ではなく
 * 問題型の要約になる（`about:blank` 以外に RFC 9457 が求める形）。
 */
object ProblemTypes {

    /** global alias も local alias も無く、S3 API でアドレスできないバケット（spec §6.5）。 */
    const val BUCKET_NOT_ADDRESSABLE: String = "urn:garage-admin-console:problem:bucket-not-addressable"

    /** そのバケットに read 以上の権限を持つアクセスキーが 1 つも無い（spec §6.4）。 */
    const val NO_USABLE_KEY: String = "urn:garage-admin-console:problem:no-usable-key"
}
