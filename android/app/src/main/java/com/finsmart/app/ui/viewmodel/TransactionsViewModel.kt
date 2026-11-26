package com.finsmart.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.finsmart.app.data.local.AppDatabase
import com.finsmart.app.data.AppRepository
import com.finsmart.app.data.RetrofitClient
import com.finsmart.app.data.local.TransactionEntity
import com.finsmart.app.data.model.ImportResult
import com.finsmart.app.utils.TokenManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import com.finsmart.app.utils.DateUtils

class TransactionsViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val api = RetrofitClient.createApi(context)
    private val db = AppDatabase.getInstance(context)
    private val tokenManager = TokenManager(context)
    private val repository = AppRepository(context, api, db, tokenManager)

    private val _transactions = MutableStateFlow<List<TransactionEntity>>(emptyList())
    val transactions: StateFlow<List<TransactionEntity>> = _transactions

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _importResult = MutableStateFlow<ImportResult?>(null)
    val importResult: StateFlow<ImportResult?> = _importResult

    private val _importCompleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val importCompleted = _importCompleted.asSharedFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init {
        viewModelScope.launch {
            repository.getTransactionsFlow().collect { list ->
                _transactions.value = list
            }
        }
        syncData()
    }

    fun syncData() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.syncTransactions(DateUtils.getCurrentMonth())
            _isLoading.value = false
        }
    }

    fun importCsv(file: File) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _importResult.value = null

            val result = repository.importCsv(file)
            if (result != null) {
                _importResult.value = result
                repository.syncTransactions(DateUtils.getCurrentMonth())
                _importCompleted.emit(Unit)
            } else {
                _error.value = "Ошибка импорта. Проверьте соединение."
            }
            _isLoading.value = false
        }
    }

    fun updateCategory(id: String, category: String) {
        viewModelScope.launch {
            repository.updateCategory(id, category)
        }
    }
}
