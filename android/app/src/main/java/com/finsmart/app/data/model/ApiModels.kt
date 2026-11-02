package com.finsmart.app.data.model
// Модели для авторизации
data class AuthRequest(val email: String, val password: String)
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val expiresIn: Int,
    val user: User
)
data class User(val id: String, val email: String)

// Модели для операций
data class TransactionResponse(
    val items: List<Transaction>,
    val total: Int,
    val limit: Int,
    val offset: Int
)

data class Transaction(
    val id: String,
    val operationId: String,
    val date: String,
    val amount: String, // Строка, так как в OpenAPI это Money
    val currency: String,
    val description: String?,
    val mcc: String?,
    val category: String,
    val categoryManual: Boolean
)

// Модели для аналитики
data class AnalyticsSummary(
    val month: String,
    val previousMonth: String,
    val totalExpense: String,
    val previousTotalExpense: String,
    val changePercent: Double?,
    val topCategory: String?,
    val transactionsCount: Int,
    val categories: List<CategorySummary>
)

data class CategorySummary(
    val category: String,
    val amount: String,
    val previousAmount: String,
    val changePercent: Double?,
    val sharePercent: Double
)

// Модели для скидок
data class OfferList(
    val items: List<Offer>,
    val catalogCheckedAt: String?
)

data class Offer(
    val id: String,
    val sourceId: String,
    val title: String,
    val category: String,
    val regularPrice: String?,
    val salePrice: String?,
    val currency: String,
    val validFrom: String?,
    val validTo: String?,
    val conditions: String?,
    val url: String,
    val region: String?,
    val checkedAt: String,
    val potentialSaving: String?
)

// Модели для целей
data class Goal(
    val id: String,
    val title: String,
    val targetAmount: String,
    val currentAmount: String,
    val progressPercent: Double,
    val deadline: String?,
    val category: String?,
    val createdAt: String,
    val updatedAt: String
)

// Модели для импорта
data class ImportResult(
    val imported: Int,
    val duplicates: Int,
    val failed: Int,
    val errors: List<ImportRowError>
)
data class ImportRowError(val line: Int, val field: String?, val reason: String)

// Модель для рекомендаций ИИ
data class Recommendation(
    val title: String,
    val message: String,
    val category: String?,
    val actionType: String,
    val actionLabel: String?,
    val source: String
)
class ApiModels {
}