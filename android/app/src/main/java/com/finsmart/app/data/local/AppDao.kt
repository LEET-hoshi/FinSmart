package com.finsmart.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Query("SELECT * FROM transactions ORDER BY date DESC")
    fun getTransactions(): Flow<List<TransactionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransactions(transactions: List<TransactionEntity>)

    @Query("DELETE FROM transactions")
    suspend fun clearTransactions()

    @Query("UPDATE transactions SET category = :category, categoryManual = 1 WHERE id = :id")
    suspend fun updateTransactionCategory(id: String, category: String)

    @Query("SELECT * FROM offers")
    fun getOffers(): Flow<List<OfferEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOffers(offers: List<OfferEntity>)

    @Query("DELETE FROM offers")
    suspend fun clearOffers()

    @Query("SELECT * FROM goals")
    fun getGoals(): Flow<List<GoalEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGoals(goals: List<GoalEntity>)

    @Query("DELETE FROM goals")
    suspend fun clearGoals()
}