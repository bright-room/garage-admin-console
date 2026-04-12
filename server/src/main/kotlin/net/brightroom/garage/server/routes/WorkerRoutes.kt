package net.brightroom.garage.server.routes

import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.brightroom.garage.server.service.GarageAdminClient

fun Route.workerRoutes(client: GarageAdminClient) {
    route("/workers") {
        post("/list") {
            val body = call.receiveText()
            val response = client.post("/v2/ListWorkers", body)
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        post("/info") {
            val body = call.receiveText()
            val response = client.post("/v2/GetWorkerInfo", body)
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        post("/variable/get") {
            val body = call.receiveText()
            val response = client.post("/v2/GetWorkerVariable", body)
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
        post("/variable/set") {
            val body = call.receiveText()
            val response = client.post("/v2/SetWorkerVariable", body)
            call.respondText(response.bodyAsText(), ContentType.Application.Json, response.status)
        }
    }
}
