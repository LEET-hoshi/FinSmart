package com.finsmart.app.data

import com.finsmart.app.data.model.*
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.*

interface ApiService {
    @POST("auth/register")
    suspend fun register(@Body request: AuthRequest): Response<AuthResponse>

    @POST("auth/login")
    suspend fun login(@Body request: AuthRequest): Response<AuthResponse>

    @Multipart
    @POST("transactions/import")
    suspend fun importCsv(@Part file: MultipartBody.Part): Response<ImportResult>

    @GET("transactions")
    suspend fun getTransactions(
        @Query("month") month: String? = null,
        @Query("category") category: String? = null,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<TransactionResponse>

    @PATCH("transactions/{id}/category")
    suspend fun updateTransactionCategory(
        @Path("id") id: String,
        @Body body: Map<String, String>
    ): Response<Transaction>

    @GET("analytics/summary")
    suspend fun getAnalyticsSummary(@Query("month") month: String): Response<AnalyticsSummary>

    @GET("offers")
    suspend fun getOffers(
        @Query("category") category: String? = null,
        @Query("region") region: String? = null
    ): Response<OfferList>

    @GET("goals")
    suspend fun getGoals(): Response<List<Goal>>

    @POST("goals")
    suspend fun createGoal(@Body body: GoalCreate): Response<Goal>

    @GET("ai/recommendation")
    suspend fun getRecommendation(@Query("month") month: String): Response<Recommendation>
}

