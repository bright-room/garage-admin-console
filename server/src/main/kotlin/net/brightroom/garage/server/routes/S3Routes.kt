package net.brightroom.garage.server.routes

import io.ktor.http.*
import io.ktor.server.request.contentType
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.brightroom.garage.server.service.GarageS3Service

fun Route.s3Routes(s3: GarageS3Service) {
    val json = Json { prettyPrint = false }

    route("/s3/{bucket}") {
        get("/objects") {
            val bucket = call.parameters["bucket"]
                ?: return@get call.respond(HttpStatusCode.BadRequest)
            val prefix = call.request.queryParameters["prefix"] ?: ""
            val delimiter = call.request.queryParameters["delimiter"] ?: "/"
            val continuationToken = call.request.queryParameters["continuationToken"]
            val maxKeys = call.request.queryParameters["maxKeys"]?.toIntOrNull() ?: 1000

            val result = s3.listObjects(bucket, prefix, delimiter, continuationToken, maxKeys)
            call.respondText(json.encodeToString(result), ContentType.Application.Json)
        }

        get("/download") {
            val bucket = call.parameters["bucket"]
                ?: return@get call.respond(HttpStatusCode.BadRequest)
            val key = call.request.queryParameters["key"]
                ?: return@get call.respond(HttpStatusCode.BadRequest)

            val data = s3.getObject(bucket, key)
            val fileName = key.substringAfterLast('/')
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName, fileName
                ).toString()
            )
            call.respondBytes(data)
        }

        post("/upload") {
            val bucket = call.parameters["bucket"]
                ?: return@post call.respond(HttpStatusCode.BadRequest)
            val key = call.request.queryParameters["key"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing key parameter")
            val contentType = call.request.contentType().toString()

            val data = call.receive<ByteArray>()
            s3.putObject(bucket, key, data, contentType)
            call.respondText("""{"key":"$key"}""", ContentType.Application.Json)
        }

        delete("/objects") {
            val bucket = call.parameters["bucket"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val key = call.request.queryParameters["key"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest)

            s3.deleteObject(bucket, key)
            call.respond(HttpStatusCode.OK)
        }
    }
}
