package ru.finsmart.server.sync

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import ru.finsmart.server.auth.userId
import ru.finsmart.server.common.receiveJson

fun Route.syncRoutes(service: SyncService) {
    post("/sync/push") {
        val request = call.receiveJson<SyncPushRequest>()
        call.respond(service.push(call.userId(), request))
    }
}
