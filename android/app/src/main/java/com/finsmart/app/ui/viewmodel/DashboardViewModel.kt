package com.finsmart.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.finsmart.app.data.local.AppDatabase
import com.finsmart.app.data.AppRepository
import com.finsmart.app.data.RetrofitClient
import com.finsmart.app.data.model.AnalyticsSummary
import com.finsmart.app.data.model.Recommendation
import com.finsmart.app.utils.TokenManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.finsmart.app.utils.DateUtils

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val api = RetrofitClient.createApi(context)
    private val db = AppDatabase.getInstance(context)
    private val tokenManager = TokenManager(context)
    private val repository = AppRepository(context, api, db, tokenManager)

    private val _analytics = MutableStateFlow<AnalyticsSummary?>(null)
    val analytics: StateFlow<AnalyticsSummary?> = _analytics

    private val _recommendation = MutableStateFlow<Recommendation?>(null)
    val recommendation: StateFlow<Recommendation?> = _recommendation

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            _isLoading.value = true

            val month = "2026-10"

            val summaryResult = repository.getAnalytics(month)
            val recommendationResult = repository.getRecommendation(month)

            if (summaryResult != null) {
                _analytics.value = summaryResult
                _error.value = null // Очищаем ошибку при успехе
            } else {
                // В авиарежиме не показываем страшную ошибку,
                // если данные уже были загружены ранее
                if (_analytics.value == null) {
                    _error.value = "Нет сети. Показаны последние данные."
                }
            }

            if (recommendationResult != null) {
                _recommendation.value = recommendationResult
            }

            _isLoading.value = false
        }
    }
}