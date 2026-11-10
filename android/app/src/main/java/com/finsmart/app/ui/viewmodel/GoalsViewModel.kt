package com.finsmart.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.finsmart.app.data.AppRepository
import com.finsmart.app.data.RetrofitClient
import com.finsmart.app.data.local.AppDatabase
import com.finsmart.app.data.model.Goal
import com.finsmart.app.data.model.GoalCreate // <-- ВАЖНЫЙ ИМПОРТ
import com.finsmart.app.utils.TokenManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class GoalsViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val api = RetrofitClient.createApi(context)
    private val db = AppDatabase.getInstance(context)
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
                // ИСПОЛЬЗУЕМ НОВУЮ МОДЕЛЬ GoalCreate ВМЕСТО mapOf
                val body = GoalCreate(
                    title = title,
                    targetAmount = targetAmount,
                    currentAmount = "0.00"
                )
                val response = api.createGoal(body)
                if (response.isSuccessful) {
                    loadGoals() // Обновляем список после создания
                } else {
                    // Для отладки: выводим код ошибки, если сервер не принял запрос
                    android.util.Log.e("GOAL_ERROR", "Ошибка: ${response.code()} ${response.errorBody()?.string()}")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            _isLoading.value = false
        }
    }
}