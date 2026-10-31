package ru.finsmart.server.common

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveNullable

suspend inline fun <reified T : Any> ApplicationCall.receiveJson(): T {
    val contentType = runCatching { request.contentType() }.getOrNull()
    if (contentType == null || !contentType.match(ContentType.Application.Json)) {
        throw ApiException(
            HttpStatusCode.UnsupportedMediaType,
            "unsupported_media_type",
            "Ожидается Content-Type: application/json",
        )
    }
    return receiveNullable<T>()
        ?: throw ApiException(HttpStatusCode.BadRequest, "validation_error", "Некорректное тело запроса")
}
