package net.brightroom.garage.server.s3

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.smithy.kotlin.runtime.net.url.Url
import net.brightroom.garage.server.config.AppConfig

/**
 * S3 API 越しのオブジェクト操作。
 *
 * spec §9 のポータブルな側であり、Garage 固有の概念を持ち込まない。
 * エンドポイント・リージョン・path-style は設定から受ける。
 *
 * クライアントは呼び出しごとに作って閉じる。資格情報がバケットごとに異なるため
 * 使い回せず、管理コンソールの操作頻度なら接続の作り直しは問題にならない。
 */
class S3ObjectStore(private val config: AppConfig.S3) {

    internal suspend fun <T> withClient(credentials: S3Credentials, block: suspend (S3Client, String) -> T): T =
        S3Client {
            region = config.region
            endpointUrl = Url.parse(config.endpoint)
            forcePathStyle = config.pathStyle
            credentialsProvider = StaticCredentialsProvider {
                accessKeyId = credentials.accessKeyId
                secretAccessKey = credentials.secretAccessKey
            }
        }.use { client -> block(client, credentials.bucketName) }
}
