package com.finsmart.app.data

import android.content.Context
import com.finsmart.app.data.local.*
import com.finsmart.app.data.model.*
import com.finsmart.app.utils.TokenManager
import kotlinx.coroutines.flow.Flow
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

class AppRepository(
    private val context: Context,
    private val api: ApiService,
    private val db: AppDatabase,
    private val tokenManager: TokenManager
) {
    private val dao = db.appDao()

    // --- Авторизация ---
    suspend fun login(email: String, pass: String): Boolean {
        return try {
            val res = api.login(AuthRequest(email, pass))
            if (res.isSuccessful && res.body() != null) {
                tokenManager.saveToken(res.body()!!.accessToken)
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    suspend fun register(email: String, pass: String): Boolean {
        return try {
            val res = api.register(AuthRequest(email, pass))
            if (res.isSuccessful && res.body() != null) {
                tokenManager.saveToken(res.body()!!.accessToken)
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    suspend fun isLoggedIn(): Boolean {
        return tokenManager.getToken() != null
    }

    suspend fun logout() {
        tokenManager.clearToken()
    }

    // --- Операции (Offline-First) ---
    // Возвращаем Flow из Room, чтобы UI обновлялся автоматически
    fun getTransactionsFlow(): Flow<List<TransactionEntity>> = dao.getTransactions()

    // Запрос к сети для обновления кэша
    suspend fun syncTransactions(month: String) {
        try {
            val res = api.getTransactions(month = month)
            if (res.isSuccessful && res.body() != null) {
                val entities = res.body()!!.items.map {
                    TransactionEntity(
                        id = it.id, operationId = it.operationId, date = it.date,
                        amount = it.amount, currency = it.currency, description = it.description,
                        mcc = it.mcc, category = it.category, categoryManual = it.categoryManual
                    )
                }
                dao.clearTransactions()
                dao.insertTransactions(entities)
            }
        } catch (e: Exception) {
            // Ошибка сети игнорируется, данные останутся из кэша
            e.printStackTrace()
        }
    }

    suspend fun updateCategory(id: String, category: String): Boolean {
        return try {
            val res = api.updateTransactionCategory(id, mapOf("category" to category))
            if (res.isSuccessful) {
                // Если успешно, обновляем локальную базу
                syncTransactions("2026-10") // В MVP хардкод месяца для простоты
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    // --- Импорт CSV ---
    suspend fun importCsv(file: File): ImportResult? {
        return try {
            val requestFile = file.asRequestBody("text/csv".toMediaTypeOrNull())
            val body = MultipartBody.Part.createFormData("file", file.name, requestFile)
            val res = api.importCsv(body)
            if (res.isSuccessful) res.body() else null
        } catch (e: Exception) {
            null
        }
    }

    // --- Скидки (Offline-First) ---
    fun getOffersFlow(): Flow<List<OfferEntity>> = dao.getOffers()

    suspend fun syncOffers(category: String? = null) {
        try {
            val res = api.getOffers(category = category)
            if (res.isSuccessful && res.body() != null) {
                val entities = res.body()!!.items.map {
                    OfferEntity(
                        id = it.id, sourceId = it.sourceId, title = it.title, category = it.category,
                        regularPrice = it.regularPrice, salePrice = it.salePrice, currency = it.currency,
                        validFrom = it.validFrom, validTo = it.validTo, conditions = it.conditions,
                        url = it.url, region = it.region, checkedAt = it.checkedAt, potentialSaving = it.potentialSaving
                    )
                }
                dao.clearOffers()
                dao.insertOffers(entities)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- Аналитика и Цели ---
    suspend fun getAnalytics(month: String): AnalyticsSummary? {
        return try {
            val res = api.getAnalyticsSummary(month)
            if (res.isSuccessful) res.body() else null
        } catch (e: Exception) { null }
    }

    suspend fun getRecommendation(month: String): Recommendation? {
        return try {
            val res = api.getRecommendation(month)
            if (res.isSuccessful) res.body() else null
        } catch (e: Exception) { null }
    }
}