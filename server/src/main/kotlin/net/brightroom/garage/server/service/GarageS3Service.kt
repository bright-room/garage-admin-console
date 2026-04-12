package net.brightroom.garage.server.service

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.*
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.collections.Attributes
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.toByteArray
import aws.smithy.kotlin.runtime.net.url.Url
import net.brightroom.garage.server.config.AppConfig
import net.brightroom.garage.shared.model.s3.S3ObjectItem
import net.brightroom.garage.shared.model.s3.S3ObjectList

class GarageS3Service(private val config: AppConfig) {
    private val client = S3Client {
        region = config.garageS3Region
        endpointUrl = Url.parse(config.garageS3Endpoint)
        credentialsProvider = StaticCredentialsProvider(
            accessKeyId = config.garageS3AccessKeyId,
            secretAccessKey = config.garageS3SecretAccessKey,
        )
        forcePathStyle = true
    }

    suspend fun listObjects(
        bucket: String,
        prefix: String = "",
        delimiter: String = "/",
        continuationToken: String? = null,
        maxKeys: Int = 1000,
    ): S3ObjectList {
        val request = ListObjectsV2Request {
            this.bucket = bucket
            this.prefix = prefix
            this.delimiter = delimiter
            this.continuationToken = continuationToken
            this.maxKeys = maxKeys
        }
        val response = client.listObjectsV2(request)
        return S3ObjectList(
            prefix = prefix,
            objects = response.contents?.map { obj ->
                S3ObjectItem(
                    key = obj.key ?: "",
                    size = obj.size ?: 0L,
                    lastModified = obj.lastModified?.toString() ?: "",
                    etag = obj.eTag,
                )
            } ?: emptyList(),
            commonPrefixes = response.commonPrefixes?.mapNotNull { it.prefix } ?: emptyList(),
            isTruncated = response.isTruncated == true,
            nextContinuationToken = response.nextContinuationToken,
        )
    }

    suspend fun deleteObject(bucket: String, key: String) {
        val request = DeleteObjectRequest {
            this.bucket = bucket
            this.key = key
        }
        client.deleteObject(request)
    }

    suspend fun getObject(bucket: String, key: String): ByteArray {
        val request = GetObjectRequest {
            this.bucket = bucket
            this.key = key
        }
        return client.getObject(request) { response ->
            response.body?.toByteArray() ?: ByteArray(0)
        }
    }

    suspend fun putObject(bucket: String, key: String, data: ByteArray, contentType: String?) {
        val request = PutObjectRequest {
            this.bucket = bucket
            this.key = key
            this.body = ByteStream.fromBytes(data)
            if (contentType != null) {
                this.contentType = contentType
            }
        }
        client.putObject(request)
    }

    fun close() {
        client.close()
    }
}

private class StaticCredentialsProvider(
    private val accessKeyId: String,
    private val secretAccessKey: String,
) : CredentialsProvider {
    override suspend fun resolve(attributes: Attributes): Credentials =
        Credentials(accessKeyId = accessKeyId, secretAccessKey = secretAccessKey)
}
