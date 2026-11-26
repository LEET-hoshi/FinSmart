package ru.finsmart.server.transactions

import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Imports the XLSX statement layout exported by the supported bank. */
object BankStatementXlsxParser {
    private val xlsxMagic = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    fun isXlsx(bytes: ByteArray): Boolean =
        bytes.size >= xlsxMagic.size && xlsxMagic.indices.all { bytes[it] == xlsxMagic[it] }

    fun parse(bytes: ByteArray, mccToCategory: Map<String, String> = emptyMap()): CsvParseResult {
        val rows = mutableListOf<ParsedTransaction>()
        val errors = mutableListOf<ImportRowError>()
        val seen = mutableSetOf<String>()
        var duplicates = 0

        try {
            XSSFWorkbook(ByteArrayInputStream(bytes)).use { workbook ->
                val sheet = workbook.getSheetAt(0) ?: throw InvalidCsvException("Выписка не содержит листа с операциями")
                val formatter = DataFormatter()
                val header = sheet.firstOrNull { formatter.formatCellValue(it.getCell(0)).trim() == "Дата операции" }
                    ?: throw InvalidCsvException("Не найден заголовок операций в Excel-выписке")

                for (row in sheet.drop(header.rowNum + 1)) {
                    val line = row.rowNum + 1
                    val dateText = formatter.formatCellValue(row.getCell(0)).trim()
                    val operationId = formatter.formatCellValue(row.getCell(3)).trim()
                    val description = formatter.formatCellValue(row.getCell(11)).trim()
                    val amountText = formatter.formatCellValue(row.getCell(12)).trim()
                    if (dateText.isEmpty() && operationId.isEmpty() && amountText.isEmpty()) continue
                    val parsed = try {
                        require(operationId.isNotEmpty() && operationId.length <= 64)
                        val amount = BigDecimal(amountText.replace("\u00A0", "").replace(" ", "").replace(',', '.'))
                        require(amount.signum() != 0)
                        val mcc = extractMcc(description)
                        ParsedTransaction(
                            operationId = operationId,
                            date = LocalDate.parse(dateText, dateFormat),
                            amount = amount.setScale(2),
                            currency = "RUB",
                            description = description.ifEmpty { null },
                            mcc = mcc,
                            categoryCode = mcc?.let(mccToCategory::get) ?: CATEGORY_OTHER,
                            isInternalTransfer = isInternalTransfer(description),
                        )
                    } catch (_: Exception) {
                        errors += ImportRowError(line, null, "Не удалось прочитать операцию из Excel-выписки")
                        null
                    }
                    if (parsed == null) continue
                    if (!seen.add(parsed.operationId)) duplicates++ else rows += parsed
                }
            }
        } catch (e: InvalidCsvException) {
            throw e
        } catch (_: Exception) {
            throw InvalidCsvException("Не удалось прочитать Excel-выписку")
        }
        return CsvParseResult(rows, duplicates, errors)
    }

    private fun extractMcc(description: String): String? =
        Regex("(?i)MCC:\\s*(\\d{4})").find(description)?.groupValues?.get(1)
}
