package com.finsmart.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey val id: String,
    val operationId: String,
    val date: String,
    val amount: String,
    val currency: String,
    val description: String?,
    val mcc: String?,
    val category: String,
    val categoryManual: Boolean
)

@Entity(tableName = "offers")
data class OfferEntity(
    @PrimaryKey val id: String,
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

@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey val id: String,
    val title: String,
    val targetAmount: String,
    val currentAmount: String,
    val progressPercent: Double,
    val deadline: String?,
    val category: String?,
    val createdAt: String,
    val updatedAt: String
)