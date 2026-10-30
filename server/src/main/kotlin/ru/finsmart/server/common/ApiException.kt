package ru.finsmart.server.common

import io.ktor.http.HttpStatusCode

class ApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
    val details: Map<String, String>? = null,
) : RuntimeException(message) {
    companion object {
        fun validation(details: Map<String, String>) =
            ApiException(HttpStatusCode.BadRequest, "validation_error", "Некорректные данные", details)

        fun unauthorized() =
            ApiException(HttpStatusCode.Unauthorized, "unauthorized", "Требуется авторизация")

        fun notFound() =
            ApiException(HttpStatusCode.NotFound, "not_found", "Объект не найден")
    }
}
