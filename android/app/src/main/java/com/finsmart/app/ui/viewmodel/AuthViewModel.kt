package com.finsmart.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.finsmart.app.data.AppRepository
import com.finsmart.app.data.local.AppDatabase
import com.finsmart.app.data.RetrofitClient
import com.finsmart.app.utils.TokenManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val api = RetrofitClient.createApi(context)
    private val db = AppDatabase.getInstance(context)
    private val tokenManager = TokenManager(context)
    private val repository = AppRepository(context, api, db, tokenManager)

    private val _isLoggedIn = MutableStateFlow<Boolean?>(null)
    val isLoggedIn: StateFlow<Boolean?> = _isLoggedIn

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init {
        checkAuth()
    }

    private fun checkAuth() {
        _isLoggedIn.value = tokenManager.getToken() != null
    }

    fun login(email: String, pass: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            val success = repository.login(email, pass)
            if (success) {
                _isLoggedIn.value = true
            } else {
                _error.value = "Неверный email или пароль"
            }
            _isLoading.value = false
        }
    }

    fun register(email: String, pass: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            val success = repository.register(email, pass)
            if (success) {
                _isLoggedIn.value = true
            } else {
                _error.value = "Ошибка регистрации (возможно, email занят)"
            }
            _isLoading.value = false
        }
    }
}