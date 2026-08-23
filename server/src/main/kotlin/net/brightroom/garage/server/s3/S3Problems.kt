package net.brightroom.garage.server.s3

/**
 * そのバケットに read 以上の権限を持つキーが 1 つも無い（spec §6.4 の縮退動作）。
 *
 * S3 ブラウザだけが縮退する 2 つの理由の 1 つ（もう 1 つは [BucketNotAddressableException]）。
 * どちらも HTTP ステータスだけでは区別できず、画面に出す案内も導線も異なるため、
 * RFC 9457 の `type` を持つ（`ProblemTypes`）。
 */
class NoUsableKeyException(val bucketId: String) : RuntimeException("バケット $bucketId にアクセスできるキーがありません")

/** global alias も local alias も無く、S3 API でアドレスできない（spec §6.5）。 */
class BucketNotAddressableException(val bucketId: String) : RuntimeException("バケット $bucketId は S3 API でアドレスできません")
