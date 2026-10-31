package ru.finsmart.server.common

import java.time.YearMonth
import java.util.UUID

object Categories {
    val CODES = setOf("groceries", "cafes", "transport", "health", "home", "entertainment", "other")
    const val FALLBACK = "other"
}

object Params {
    private val MONTH_REGEX = Regex("^\\d{4}-(0[1-9]|1[0-2])$")

    fun month(raw: String?, name: String = "month"): YearMonth? {
        if (raw == null) return null
        if (!MONTH_REGEX.matches(raw)) {
            throw ApiException.validation(mapOf(name to "Ожидается месяц в формате YYYY-MM"))
        }
        return YearMonth.parse(raw)
    }

    fun requiredMonth(raw: String?, name: String = "month"): YearMonth =
        month(raw, name) ?: throw ApiException.validation(mapOf(name to "Параметр обязателен"))

    fun category(raw: String?, name: String = "category"): String? {
        if (raw == null) return null
        if (raw !in Categories.CODES) {
            throw ApiException.validation(mapOf(name to "Неизвестная категория"))
        }
        return raw
    }

    fun int(raw: String?, name: String, default: Int, min: Int, max: Int): Int {
        if (raw == null) return default
        val value = raw.toIntOrNull()
        if (value == null || value < min || value > max) {
            throw ApiException.validation(mapOf(name to "Ожидается целое число от $min до $max"))
        }
        return value
    }

    fun idOrNotFound(raw: String?): UUID =
        raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?.takeIf { it.toString() == raw.lowercase() }
            ?: throw ApiException.notFound()
}
