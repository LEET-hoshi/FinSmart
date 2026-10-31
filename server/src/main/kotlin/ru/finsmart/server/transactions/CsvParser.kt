package ru.finsmart.server.transactions

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

sealed interface CsvLineResult

data class CsvRow(
    val line: Int,
    val operationId: String,
    val date: LocalDate,
    val amount: BigDecimal,
    val currency: String,
    val description: String?,
    val mcc: String?,
) : CsvLineResult

data class CsvRowError(
    val line: Int,
    val field: String?,
    val reason: String,
) : CsvLineResult

sealed interface CsvParseResult {
    data class Parsed(val rows: List<CsvRow>, val errors: List<CsvRowError>) : CsvParseResult
    data class Invalid(val message: String, val details: Map<String, String>? = null) : CsvParseResult
    data object TooManyRows : CsvParseResult
}

object CsvParser {
    val HEADER = listOf("operation_id", "date", "amount", "currency", "description", "mcc")

    private const val MAX_DESCRIPTION_LENGTH = 500
    private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val AMOUNT_REGEX = Regex("^-?\\d{1,12}(\\.\\d{1,2})?$")
    private val MCC_REGEX = Regex("^\\d{4}$")
    private val DATE_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)

    fun parse(bytes: ByteArray, maxRows: Int): CsvParseResult {
        val text = decodeUtf8(bytes) ?: return CsvParseResult.Invalid("Файл должен быть в кодировке UTF-8")
        val lines = text.removePrefix("﻿").split('\n').map { it.removeSuffix("\r") }

        val header = lines.firstOrNull()?.takeIf { it.isNotBlank() }?.let { splitLine(it) }
            ?.map { it.trim().lowercase() }
            ?: return CsvParseResult.Invalid("Файл пустой")
        if (header != HEADER) {
            return CsvParseResult.Invalid("Неверный заголовок CSV", mapOf("expected" to HEADER.joinToString(",")))
        }

        val dataLines = lines.withIndex().drop(1).filter { it.value.isNotBlank() }
        if (dataLines.size > maxRows) return CsvParseResult.TooManyRows

        val rows = mutableListOf<CsvRow>()
        val errors = mutableListOf<CsvRowError>()
        for ((index, raw) in dataLines) {
            when (val result = parseRow(index + 1, raw)) {
                is CsvRow -> rows += result
                is CsvRowError -> errors += result
            }
        }
        return CsvParseResult.Parsed(rows, errors)
    }

    private fun parseRow(line: Int, raw: String): CsvLineResult {
        val cells = splitLine(raw)?.map { it.trim() }
            ?: return CsvRowError(line, null, "Незакрытая кавычка")
        if (cells.size != HEADER.size) {
            return CsvRowError(line, null, "Ожидается ${HEADER.size} столбцов, получено ${cells.size}")
        }
        val (operationId, dateRaw, amountRaw, currency, description, mccRaw) = cells

        if (!UUID_REGEX.matches(operationId)) {
            return CsvRowError(line, "operation_id", "operation_id должен быть UUID")
        }
        val date = try {
            LocalDate.parse(dateRaw, DATE_FORMAT)
        } catch (_: DateTimeParseException) {
            return CsvRowError(line, "date", "Дата должна быть в формате YYYY-MM-DD")
        }
        if (!AMOUNT_REGEX.matches(amountRaw)) {
            return CsvRowError(line, "amount", "Сумма должна быть числом с не более чем 2 знаками после точки")
        }
        val amount = BigDecimal(amountRaw)
        if (amount.signum() == 0) {
            return CsvRowError(line, "amount", "Сумма не может быть нулевой")
        }
        if (currency != "RUB") {
            return CsvRowError(line, "currency", "Поддерживается только валюта RUB")
        }
        if (description.length > MAX_DESCRIPTION_LENGTH) {
            return CsvRowError(line, "description", "Описание длиннее $MAX_DESCRIPTION_LENGTH символов")
        }
        if (mccRaw.isNotEmpty() && !MCC_REGEX.matches(mccRaw)) {
            return CsvRowError(line, "mcc", "MCC должен состоять из 4 цифр")
        }

        return CsvRow(
            line = line,
            operationId = operationId.lowercase(),
            date = date,
            amount = amount,
            currency = currency,
            description = description.ifEmpty { null },
            mcc = mccRaw.ifEmpty { null },
        )
    }

    private operator fun <T> List<T>.component6(): T = this[5]

    private fun splitLine(line: String): List<String>? {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    cells += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        if (inQuotes) return null
        cells += current.toString()
        return cells
    }

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}
