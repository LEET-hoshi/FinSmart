package com.finsmart.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.finsmart.app.data.local.AppDatabase
import com.finsmart.app.data.AppRepository
import com.finsmart.app.data.RetrofitClient
import com.finsmart.app.data.local.OfferEntity
import com.finsmart.app.utils.TokenManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class OffersViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val api = RetrofitClient.createApi(context)
    private val db = AppDatabase.getInstance(context)
    private val tokenManager = TokenManager(context)
    private val repository = AppRepository(context, api, db, tokenManager)

    private val currentCategory = MutableStateFlow<String?>(null)

    val offers: StateFlow<List<OfferEntity>> = currentCategory.flatMapLatest { cat ->
        repository.getOffersFlow(cat)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun syncData(category: String? = null) {
        currentCategory.value = category
        viewModelScope.launch {
            _isLoading.value = true
            _isOffline.value = false
            _error.value = null

            val success = repository.syncOffers(category)
            if (!success) {
                val cachedData = offers.value
                if (cachedData.isNotEmpty()) {
                    _isOffline.value = true
                } else {
                    _error.value = "Ошибка загрузки скидок. Проверьте сеть."
                }
            }
            _isLoading.value = false
        }
    }
}