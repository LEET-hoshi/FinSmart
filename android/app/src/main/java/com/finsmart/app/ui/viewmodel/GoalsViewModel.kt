package com.finsmart.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.finsmart.app.data.local.AppDatabase as LocalDb
import com.finsmart.app.data.AppRepository
import com.finsmart.app.data.RetrofitClient
import com.finsmart.app.data.model.Goal
import com.finsmart.app.utils.TokenManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class GoalsViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val api = RetrofitClient.createApi(context)
    // ИСПРАВЛЕННЫЙ ИМПОРТ:
    private val db = LocalDb.getInstance(context)
    private val tokenManager = TokenManager(context)
    private val repository = AppRepository(context, api, db, tokenManager)

    private val _goals = MutableStateFlow<List<Goal>>(emptyList())
    val goals: StateFlow<List<Goal>> = _goals

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    init {
        loadGoals()
    }

    private fun loadGoals() {
        viewModelScope.launch {
            _isLoading.value = true
            // В нашем репозитории пока нет метода сохранения целей в Room,
            // поэтому для MVP мы просто запрашиваем их из сети
            try {
                val response = api.getGoals()
                if (response.isSuccessful && response.body() != null) {
                    _goals.value = response.body()!!
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            _isLoading.value = false
        }
    }

    fun createGoal(title: String, targetAmount: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val body = mapOf(
                    "title" to title,
                    "targetAmount" to targetAmount,
                    "currentAmount" to "0.00"
                )
                val response = api.createGoal(body)
                if (response.isSuccessful) {
                    loadGoals() // Обновляем список после создания
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            _isLoading.value = false
        }
    }
}