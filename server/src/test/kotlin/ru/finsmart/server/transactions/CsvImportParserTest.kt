package ru.finsmart.server.transactions

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CsvImportParserTest {
    private val mcc = mapOf("5411" to "groceries", "5812" to "cafes", "4111" to "transport")

    private fun parse(vararg lines: String) =
        CsvImportParser.parse((listOf(CSV_HEADER) + lines).joinToString("\n").toByteArray(), mcc)

    @Test
    fun valid_rows_are_imported_with_category_by_mcc() {
        val result = parse(
            "abc-001,2026-10-01,-350.50,RUB,Магазин,5411",
            "abc-002,2026-10-02,-89,RUB,Метро,4111",
        )

        assertEquals(2, result.rows.size)
        assertEquals(0, result.errors.size)
        assertEquals("groceries", result.rows[0].categoryCode)
        assertEquals(BigDecimal("-89.00"), result.rows[1].amount)
    }

    @Test
    fun unknown_or_empty_mcc_falls_back_to_other() {
        val result = parse(
            "mcc-001,2026-10-01,-500.00,RUB,Нет в справочнике,9999",
            "mcc-002,2026-10-02,-250.00,RUB,MCC не указан,",
        )

        assertEquals(listOf("other", "other"), result.rows.map { it.categoryCode })
        assertNull(result.rows[1].mcc)
    }

    @Test
    fun repeated_operation_id_in_file_keeps_first_row_and_counts_duplicate() {
        val result = parse(
            "dup-001,2026-10-01,-350.50,RUB,Магазин,5411",
            "dup-002,2026-10-02,-1200.00,RUB,Кафе,5812",
            "dup-001,2026-10-01,-350.50,RUB,Магазин,5411",
            "dup-002,2026-10-02,-9999.00,RUB,Другая сумма,5812",
        )

        assertEquals(2, result.rows.size)
        assertEquals(2, result.duplicatesInFile)
        assertEquals(BigDecimal("-1200.00"), result.rows[1].amount)
    }

    @Test
    fun invalid_rows_are_reported_with_file_line_numbers_and_do_not_stop_valid_rows() {
        val result = parse(
            "inv-001,2026-10-01,-350.50,RUB,Корректная,5411",
            "inv-002,01.10.2026,-100.00,RUB,Дата,5411",
            "inv-003,2026-10-03,-12.345,RUB,Три знака,5411",
            "inv-004,2026-10-04,0.00,RUB,Ноль,5411",
            "inv-005,2026-10-05,-50.00,USD,Валюта,5411",
            "inv-006,2026-10-06,-50.00,RUB,MCC,54a1",
            ",2026-10-07,-50.00,RUB,Нет id,5411",
            "inv-008,2026-10-08,-75.00,RUB,Корректная,5812",
        )

        assertEquals(listOf("inv-001", "inv-008"), result.rows.map { it.operationId })
        assertEquals(
            listOf(3 to "date", 4 to "amount", 5 to "amount", 6 to "currency", 7 to "mcc", 8 to "operation_id"),
            result.errors.map { it.line to it.field },
        )
    }

    @Test
    fun quoted_description_may_contain_comma() {
        val result = parse("""q-001,2026-10-01,-10.00,RUB,"Хлеб, молоко",5411""")

        assertEquals("Хлеб, молоко", result.rows.single().description)
    }

    @Test
    fun income_row_is_accepted_and_has_positive_amount() {
        val result = parse("abc-010,2026-10-20,50000.00,RUB,Зарплата,")

        assertEquals(BigDecimal("50000.00"), result.rows.single().amount)
    }

    @Test
    fun wrong_header_is_invalid_csv() {
        val error = assertFailsWith<InvalidCsvException> {
            CsvImportParser.parse("id,date\n1,2026-10-01".toByteArray(), mcc)
        }

        assertEquals(CSV_HEADER, error.details?.get("expected"))
    }

    @Test
    fun empty_file_is_invalid_csv() {
        assertFailsWith<InvalidCsvException> { CsvImportParser.parse(ByteArray(0), mcc) }
    }

    @Test
    fun utf8_bom_before_header_is_tolerated() {
        val bytes = ("﻿" + CSV_HEADER + "\nabc-001,2026-10-01,-1.00,RUB,x,5411").toByteArray()

        assertEquals(1, CsvImportParser.parse(bytes, mcc).rows.size)
    }

    @Test
    fun more_than_5000_rows_is_too_large() {
        val body = (1..5001).joinToString("\n") { "id-$it,2026-10-01,-1.00,RUB,x,5411" }

        assertFailsWith<CsvTooLargeException> {
            CsvImportParser.parse("$CSV_HEADER\n$body".toByteArray(), mcc)
        }
    }

    @Test
    fun file_over_one_megabyte_is_too_large() {
        assertFailsWith<CsvTooLargeException> { CsvImportParser.parse(ByteArray(CSV_MAX_BYTES + 1), mcc) }
    }
}
