package ru.finsmart.server.transactions

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException

const val CSV_HEADER = "operation_id,date,amount,currency,description,mcc"
const val CSV_MAX_BYTES = 1024 * 1024
const val CSV_MAX_ROWS = 5000
const val CATEGORY_OTHER = "other"

data class ParsedTransaction(
    val operationId: String,
    val date: LocalDate,
    val amount: BigDecimal,
    val currency: String,
    val description: String?,
    val mcc: String?,
    val categoryCode: String,
)

@Serializable
data class ImportRowError(val line: Int, val field: String?, val reason: String)

data class CsvParseResult(
    val rows: List<ParsedTransaction>,
    val duplicatesInFile: Int,
    val errors: List<ImportRowError>,
)

/** Файл целиком непригоден: нет данных, неверный заголовок. Соответствует 400 `invalid_csv`. */
class InvalidCsvException(message: String, val details: Map<String, String>? = null) : RuntimeException(message)

/** Файл больше лимитов контракта. Соответствует 413 `file_too_large`. */
class CsvTooLargeException(message: String) : RuntimeException(message)

/**
 * Разбор CSV-выписки по правилам `docs/api/openapi.yaml` (POST /transactions/import).
 * Работа с БД не нужна: дубликаты с уже сохранёнными операциями определяет слой хранения,
 * здесь учитываются только повторы `operation_id` внутри одного файла (побеждает первая строка).
 */
object CsvImportParser {
    private val moneyPattern = Regex("^-?\\d+(\\.\\d{1,2})?$")
    private val mccPattern = Regex("^\\d{4}$")

    fun parse(content: ByteArray, mccToCategory: Map<String, String>): CsvParseResult {
        if (content.size > CSV_MAX_BYTES) throw CsvTooLargeException("Файл больше 1 МБ")
        val text = content.toString(Charsets.UTF_8).removePrefix("﻿")
        val lines = text.lines().let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
        if (lines.isEmpty()) throw InvalidCsvException("Пустой файл")
        if (lines.first().trim() != CSV_HEADER) {
            throw InvalidCsvException("Неверный заголовок CSV", mapOf("expected" to CSV_HEADER))
        }
        if (lines.size - 1 > CSV_MAX_ROWS) throw CsvTooLargeException("Больше $CSV_MAX_ROWS строк данных")

        val rows = ArrayList<ParsedTransaction>()
        val errors = ArrayList<ImportRowError>()
        val seen = HashSet<String>()
        var duplicates = 0

        lines.drop(1).forEachIndexed { index, raw ->
            val line = index + 2 // заголовок — строка 1
            if (raw.isBlank()) return@forEachIndexed
            val cells = splitCsvLine(raw)
            if (cells == null || cells.size != 6) {
                errors += ImportRowError(line, null, "Ожидалось 6 столбцов")
                return@forEachIndexed
            }
            val (operationId, date, amount, currency, description, mcc) = cells
            val error = validateRow(line, operationId, date, amount, currency, mcc)
            if (error != null) {
                errors += error
                return@forEachIndexed
            }
            if (!seen.add(operationId.trim())) {
                duplicates++
                return@forEachIndexed
            }
            val mccValue = mcc.trim().ifEmpty { null }
            rows += ParsedTransaction(
                operationId = operationId.trim(),
                date = LocalDate.parse(date.trim()),
                amount = BigDecimal(amount.trim()).setScale(2),
                currency = currency.trim(),
                description = description.trim().ifEmpty { null },
                mcc = mccValue,
                categoryCode = mccValue?.let(mccToCategory::get) ?: CATEGORY_OTHER,
            )
        }
        return CsvParseResult(rows, duplicates, errors)
    }

    private fun validateRow(
        line: Int,
        operationId: String,
        date: String,
        amount: String,
        currency: String,
        mcc: String,
    ): ImportRowError? {
        val id = operationId.trim()
        if (id.isEmpty() || id.length > 64) {
            return ImportRowError(line, "operation_id", "operation_id обязателен и не длиннее 64 символов")
        }
        try {
            LocalDate.parse(date.trim())
        } catch (_: DateTimeParseException) {
            return ImportRowError(line, "date", "Дата должна быть в формате YYYY-MM-DD")
        }
        val amountText = amount.trim()
        if (!moneyPattern.matches(amountText)) {
            return ImportRowError(line, "amount", "Сумма должна быть числом с не более чем 2 знаками после точки")
        }
        if (BigDecimal(amountText).signum() == 0) {
            return ImportRowError(line, "amount", "Сумма не может быть нулевой")
        }
        if (currency.trim() != "RUB") {
            return ImportRowError(line, "currency", "Поддерживается только валюта RUB")
        }
        val mccText = mcc.trim()
        if (mccText.isNotEmpty() && !mccPattern.matches(mccText)) {
            return ImportRowError(line, "mcc", "MCC должен состоять из 4 цифр или быть пустым")
        }
        return null
    }

    /** Разбор одной строки CSV с учётом кавычек (RFC 4180, без переносов строк внутри поля). */
    internal fun splitCsvLine(line: String): List<String>? {
        val cells = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && line.getOrNull(i + 1) == '"' -> { current.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { cells += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        if (inQuotes) return null
        cells += current.toString()
        return cells
    }
}

private operator fun <T> List<T>.component6(): T = this[5]
