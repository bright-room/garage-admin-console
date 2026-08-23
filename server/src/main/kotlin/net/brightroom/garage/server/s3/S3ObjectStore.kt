package net.brightroom.garage.server.s3

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.DeleteObjectRequest
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.ListObjectsV2Request
import aws.sdk.kotlin.services.s3.model.ListObjectsV2Response
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.smithy.kotlin.runtime.client.config.RequestHttpChecksumConfig
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.asByteStream
import aws.smithy.kotlin.runtime.net.url.Url
import net.brightroom.garage.server.config.AppConfig
import net.brightroom.garage.shared.model.s3.ObjectListing
import net.brightroom.garage.shared.model.s3.StoredObject
import java.io.InputStream
import kotlin.time.Instant

/** 1 ページの上限。画面は「続きを読み込む」で継続する。 */
private const val PAGE_SIZE = 1_000

/** フォルダの区切り。S3 に階層は無く、これは一覧のときの見せ方の問題である。 */
private const val DELIMITER = "/"

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

    suspend fun list(credentials: S3Credentials, prefix: String, continuationToken: String?): ObjectListing =
        withClient(credentials) { client, bucket ->
            val response = client.listObjectsV2(
                ListObjectsV2Request {
                    this.bucket = bucket
                    this.prefix = prefix.ifEmpty { null }
                    this.continuationToken = continuationToken
                    delimiter = DELIMITER
                    maxKeys = PAGE_SIZE
                },
            )

            response.toListing(prefix, credentials.keyName)
        }

    /**
     * オブジェクトを置く。
     *
     * [stream] は読み切らずにそのまま S3 へ流す。サーバーのメモリにファイル全体を
     * 載せないため、大きなファイルでも詰まらない。
     */
    suspend fun put(
        credentials: S3Credentials,
        key: String,
        contentType: String,
        contentLength: Long,
        stream: InputStream,
    ) {
        withClient(credentials) { client, bucket ->
            client.putObject(
                PutObjectRequest {
                    this.bucket = bucket
                    this.key = key
                    this.contentType = contentType
                    this.contentLength = contentLength
                    body = stream.asByteStream(contentLength)
                },
            )
        }
    }

    suspend fun delete(credentials: S3Credentials, key: String) {
        withClient(credentials) { client, bucket ->
            client.deleteObject(
                DeleteObjectRequest {
                    this.bucket = bucket
                    this.key = key
                },
            )
        }
    }

    /**
     * オブジェクトを取り出す。
     *
     * 本文は [block] の中でだけ有効なので、レスポンスへの書き出しもその中で行う。
     * 呼び出し側は受け取った [ByteStream] をそのまま出力に流すこと。
     */
    suspend fun <T> download(
        credentials: S3Credentials,
        key: String,
        block: suspend (contentType: String, body: ByteStream) -> T,
    ): T = withClient(credentials) { client, bucket ->
        client.getObject(
            GetObjectRequest {
                this.bucket = bucket
                this.key = key
            },
        ) { response ->
            block(
                response.contentType ?: "application/octet-stream",
                response.body ?: ByteStream.fromBytes(ByteArray(0)),
            )
        }
    }

    private suspend fun <T> withClient(credentials: S3Credentials, block: suspend (S3Client, String) -> T): T =
        S3Client {
            region = config.region
            endpointUrl = Url.parse(config.endpoint)
            forcePathStyle = config.pathStyle
            // SDK の既定は、事前にハッシュできない（＝リプレイ不能な）ストリームに対して
            // 既定でチェックサムトレーラ付きの aws-chunked エンコーディングを使う。Garage v2.3.0
            // はこれを解釈できず InvalidRequest を返すため、チェックサムを明示要求時のみに絞る
            // （`call.receiveStream()` はリプレイ不能なので、この経路を常に通る）
            requestChecksumCalculation = RequestHttpChecksumConfig.WHEN_REQUIRED
            credentialsProvider = StaticCredentialsProvider {
                accessKeyId = credentials.accessKeyId
                secretAccessKey = credentials.secretAccessKey
            }
        }.use { client -> block(client, credentials.bucketName) }
}

/**
 * SDK の応答をコンソールの一覧に写す。
 *
 * 接頭辞そのもののキー（0 バイトのフォルダマーカー）は、そのフォルダを開いたときに
 * 自分自身として現れるため落とす。
 */
internal fun ListObjectsV2Response.toListing(prefix: String, keyName: String): ObjectListing = ObjectListing(
    prefix = prefix,
    folders = commonPrefixes.orEmpty().mapNotNull { it.prefix },
    objects = contents.orEmpty()
        .mapNotNull { entry ->
            val key = entry.key ?: return@mapNotNull null
            if (key == prefix) return@mapNotNull null

            StoredObject(
                key = key,
                size = entry.size ?: 0,
                lastModified = entry.lastModified?.let {
                    Instant.fromEpochSeconds(it.epochSeconds, it.nanosecondsOfSecond)
                },
                etag = entry.eTag,
            )
        },
    nextToken = nextContinuationToken,
    keyName = keyName,
)
