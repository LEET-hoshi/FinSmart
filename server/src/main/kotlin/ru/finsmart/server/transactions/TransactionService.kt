package ru.finsmart.server.transactions

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.finsmart.server.common.ApiException
import ru.finsmart.server.common.Categories
import java.util.UUID

class TransactionService(private val repository: TransactionRepository) {

    suspend fun import(userId: UUID, bytes: ByteArray): ImportResultResponse {
        val parsed = try {
            val mccCategories = repository.mccCategories()
            withContext(Dispatchers.Default) { CsvImportParser.parse(bytes, mccCategories) }
        } catch (e: InvalidCsvException) {
            throw ApiException(HttpStatusCode.BadRequest, "invalid_csv", e.message ?: "Неверный CSV", e.details)
        } catch (e: CsvTooLargeException) {
            throw fileTooLarge()
        }

        val imported = repository.insertNew(userId, parsed.rows)
        val alreadyStored = parsed.rows.size - imported

        return ImportResultResponse(
            imported = imported,
            duplicates = parsed.duplicatesInFile + alreadyStored,
            failed = parsed.errors.size,
            errors = parsed.errors.map { ImportRowErrorResponse(it.line, it.field, it.reason) },
        )
    }

    suspend fun list(userId: UUID, filter: TransactionFilter): TransactionPageResponse =
        repository.find(userId, filter)

    suspend fun updateCategory(userId: UUID, id: UUID, request: CategoryUpdateRequest): TransactionResponse {
        val category = request.category
        if (category == null || category !in Categories.CODES) {
            throw ApiException.validation(mapOf("category" to "Неизвестная категория"))
        }
        return repository.updateCategory(userId, id, category) ?: throw ApiException.notFound()
    }

    companion object {
        const val MAX_FILE_BYTES = 1_048_576
        const val MAX_ROWS = 5000

        fun fileTooLarge() = ApiException(
            HttpStatusCode.PayloadTooLarge,
            "file_too_large",
            "Файл слишком большой: допускается до 1 МБ и до $MAX_ROWS строк",
        )
    }
}
