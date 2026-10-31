package ru.finsmart.server.transactions

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.finsmart.server.auth.userId
import ru.finsmart.server.common.ApiException
import ru.finsmart.server.common.Params
import ru.finsmart.server.common.receiveJson
import java.io.IOException

fun Route.transactionRoutes(service: TransactionService) {
    route("/transactions") {
        post("/import") {
            val bytes = call.receiveCsvFile()
            call.respond(service.import(call.userId(), bytes))
        }
        get {
            val params = call.request.queryParameters
            val filter = TransactionFilter(
                month = Params.month(params["month"]),
                category = Params.category(params["category"]),
                limit = Params.int(params["limit"], "limit", default = 50, min = 1, max = 200),
                offset = Params.int(params["offset"], "offset", default = 0, min = 0, max = Int.MAX_VALUE),
            )
            call.respond(service.list(call.userId(), filter))
        }
        patch("/{id}/category") {
            val id = Params.idOrNotFound(call.parameters["id"])
            val request = call.receiveJson<CategoryUpdateRequest>()
            call.respond(service.updateCategory(call.userId(), id, request))
        }
    }
}

private suspend fun ApplicationCall.receiveCsvFile(): ByteArray {
    val contentType = runCatching { request.contentType() }.getOrNull()
    if (contentType == null || !contentType.match(ContentType.MultiPart.FormData)) {
        throw ApiException(
            HttpStatusCode.UnsupportedMediaType,
            "unsupported_media_type",
            "Ожидается multipart/form-data с полем file",
        )
    }
    val maxBody = TransactionService.MAX_FILE_BYTES + MULTIPART_OVERHEAD_BYTES
    if ((request.contentLength() ?: 0L) > maxBody) throw TransactionService.fileTooLarge()

    var file: ByteArray? = null
    try {
        receiveMultipart(formFieldLimit = maxBody).forEachPart { part ->
            try {
                if (file == null && part is PartData.FileItem && part.name == "file") {
                    file = withContext(Dispatchers.IO) {
                        part.provider().toInputStream().use { it.readNBytes(TransactionService.MAX_FILE_BYTES + 1) }
                    }
                }
            } finally {
                part.dispose()
            }
        }
    } catch (e: IOException) {
        if (e.message?.contains("exceeds limit") == true) throw TransactionService.fileTooLarge()
        throw ApiException(HttpStatusCode.BadRequest, "invalid_csv", "Не удалось прочитать файл")
    }

    val bytes = file ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_csv", "Файл не передан: ожидается поле file")
    if (bytes.size > TransactionService.MAX_FILE_BYTES) throw TransactionService.fileTooLarge()
    if (bytes.isEmpty()) throw ApiException(HttpStatusCode.BadRequest, "invalid_csv", "Файл пустой")
    return bytes
}

private const val MULTIPART_OVERHEAD_BYTES = 64L * 1024
