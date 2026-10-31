package ru.finsmart.server.common

import io.ktor.http.HttpStatusCode

/** Ошибка, которую StatusPages превращает в ответ формата [ApiError]. */
class ApiException(
    val status: HttpStatusCode,
    val code: String,
    message: String,
    val details: Map<String, String>? = null,
) : RuntimeException(message)

fun validationError(field: String, reason: String) =
    ApiException(HttpStatusCode.BadRequest, "validation_error", "Некорректные данные", mapOf(field to reason))

fun notFound() = ApiException(HttpStatusCode.NotFound, "not_found", "Объект не найден")

fun unauthorized() = ApiException(HttpStatusCode.Unauthorized, "unauthorized", "Требуется авторизация")
