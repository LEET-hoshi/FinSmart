package com.finsmart.app.data.model

data class AuthRequest(val email: String, val password: String)
data class RefreshRequest(val refreshToken: String)

data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val expiresIn: Int,
    val user: User
)
data class User(val id: String, val email: String)

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
    val amount: String,
    val currency: String,
    val description: String?,
    val mcc: String?,
    val category: String,
    val categoryManual: Boolean
)

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

data class Goal(
    val id: String,
    val title: String,
    val targetAmount: String,
    val currentAmount: String,
    val progressPercent: Double,
    val deadline: String?,
    val category: String?,
    val type: String,
    val createdAt: String,
    val updatedAt: String
)

data class GoalCreate(
    val title: String,
    val targetAmount: String,
    val currentAmount: String? = null,
    val deadline: String? = null,
    val category: String? = null,
    val type: String = "saving"
)

data class ImportResult(
    val imported: Int,
    val duplicates: Int,
    val failed: Int,
    val errors: List<ImportRowError>
)
data class ImportRowError(val line: Int, val field: String?, val reason: String)

data class Recommendation(
    val title: String,
    val message: String,
    val category: String?,
    val actionType: String,
    val actionLabel: String?,
    val source: String
)