package com.finsmart.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.finsmart.app.data.local.AppDatabase
import com.finsmart.app.data.AppRepository
import com.finsmart.app.data.RetrofitClient
import com.finsmart.app.data.local.OfferEntity
import com.finsmart.app.utils.TokenManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class OffersViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val api = RetrofitClient.createApi(context)
    private val db = AppDatabase.getInstance(context)
    private val tokenManager = TokenManager(context)
    private val repository = AppRepository(context, api, db, tokenManager)

    private val _offers = MutableStateFlow<List<OfferEntity>>(emptyList())
    val offers: StateFlow<List<OfferEntity>> = _offers

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    // Новые состояния для P1
    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init {
        // Слушаем изменения из базы (кэш)
        viewModelScope.launch {
            repository.getOffersFlow().collect { list ->
                _offers.value = list
            }
        }
    }

    fun syncData(category: String? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            _isOffline.value = false
            _error.value = null

            // Проверяем, есть ли данные в кэше, чтобы знать, что показать при ошибке
            val hasCache = _offers.value.isNotEmpty()

            val success = repository.syncOffers(category) // Нужно, чтобы repo возвращал Boolean
            if (!success) {
                if (hasCache) {
                    _isOffline.value = true // Нет сети, но есть кэш
                } else {
                    _error.value = "Ошибка загрузки скидок. Проверьте сеть."
                }
            }
            _isLoading.value = false
        }
    }
}