package ru.finsmart.server.transactions

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class StatementFormatParityTest {
    private val categories = mapOf("5411" to "groceries", "5812" to "cafes")

    @Test
    fun csv_and_xlsx_normalize_to_the_same_transaction_shape() {
        val description = "Оплата в Магнит, MCC: 5411"
        val csv = CsvImportParser.parse(
            "$CSV_HEADER\nshop-001,2026-11-25,-350.50,RUB,\"$description\",5411".toByteArray(),
            categories,
        )
        val xlsx = BankStatementXlsxParser.parse(xlsxStatement(description), categories)

        val csvRow = csv.rows.single()
        val xlsxRow = xlsx.rows.single()
        assertEquals(csvRow.operationId, xlsxRow.operationId)
        assertEquals(csvRow.date, xlsxRow.date)
        assertEquals(csvRow.amount, xlsxRow.amount)
        assertEquals(csvRow.mcc, xlsxRow.mcc)
        assertEquals(csvRow.categoryCode, xlsxRow.categoryCode)
        assertEquals(csvRow.isInternalTransfer, xlsxRow.isInternalTransfer)
    }

    @Test
    fun internal_transfer_is_marked_in_both_formats() {
        val description = "Внутрибанковский перевод между счетами"
        val csv = CsvImportParser.parse(
            "$CSV_HEADER\ntransfer-001,2026-11-25,-500.00,RUB,$description,".toByteArray(),
            categories,
        )
        val xlsx = BankStatementXlsxParser.parse(xlsxStatement(description, "-500.00", "transfer-001"), categories)

        assertEquals(true, csv.rows.single().isInternalTransfer)
        assertEquals(true, xlsx.rows.single().isInternalTransfer)
    }

    private fun xlsxStatement(
        description: String,
        amount: String = "-350.50",
        operationId: String = "shop-001",
    ): ByteArray = ByteArrayOutputStream().use { output ->
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("Операции")
            sheet.createRow(0).createCell(0).setCellValue("Дата операции")
            val row = sheet.createRow(1)
            row.createCell(0).setCellValue("25.11.2026")
            row.createCell(3).setCellValue(operationId)
            row.createCell(11).setCellValue(description)
            row.createCell(12).setCellValue(amount)
            workbook.write(output)
        }
        output.toByteArray()
    }
}
