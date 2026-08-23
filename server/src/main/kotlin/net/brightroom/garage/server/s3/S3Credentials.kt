package net.brightroom.garage.server.s3

/**
 * admin token から導出した S3 の資格情報（spec §6.4）。
 *
 * **この型はブラウザに返してはならない。** [keyName] だけは
 * 「どのキーで見ているか」を画面に出すために使う（spec §6.4 の 6）。
 *
 * @param bucketName S3 API に渡すバケット名。global alias か local alias（spec §6.5）。
 */
data class S3Credentials(
    val accessKeyId: String,
    val secretAccessKey: String,
    val keyName: String,
    val bucketName: String,
) {
    /** ログや例外に混ぜても secret が出ないようにする。 */
    override fun toString(): String = "S3Credentials(keyName=$keyName, bucketName=$bucketName)"
}
