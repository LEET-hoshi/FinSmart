package ru.finsmart.server.transactions

import kotlinx.serialization.Serializable

@Serializable
data class ImportRowErrorResponse(
    val line: Int,
    val field: String?,
    val reason: String,
)

@Serializable
data class ImportResultResponse(
    val imported: Int,
    val duplicates: Int,
    val failed: Int,
    val errors: List<ImportRowErrorResponse>,
)

@Serializable
data class TransactionResponse(
    val id: String,
    val operationId: String,
    val date: String,
    val amount: String,
    val currency: String,
    val description: String?,
    val mcc: String?,
    val category: String,
    val categoryManual: Boolean,
)

@Serializable
data class TransactionPageResponse(
    val items: List<TransactionResponse>,
    val total: Long,
    val limit: Int,
    val offset: Int,
)

@Serializable
data class CategoryUpdateRequest(
    val category: String? = null,
)
