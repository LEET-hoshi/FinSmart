package com.finsmart.app.data

import android.content.Context
import com.finsmart.app.utils.TokenManager
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {
    private const val BASE_URL = "http://10.0.2.2:8080/"
    private var tokenManager: TokenManager? = null

    fun createApi(context: Context): ApiService {
        if (tokenManager == null) {
            tokenManager = TokenManager(context)
        }

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .addInterceptor { chain ->
                val token = tokenManager?.getToken()
                val request = chain.request().newBuilder()
                if (token != null) {
                    request.addHeader("Authorization", "Bearer $token")
                }
                chain.proceed(request.build())
            }
            // Добавляем обработчик 401
            .authenticator(object : Authenticator {
                override fun authenticate(route: Route?, response: Response): Request? {
                    // Если это уже повторная попытка - не зацикливаемся
                    if (response.request.header("Authorization") != null && responseCount(response) >= 2) {
                        return null
                    }
                    tokenManager?.clearToken()
                    return null
                }
            })
            .build()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    // Вспомогательная функция для подсчета попыток
    private fun responseCount(response: Response): Int {
        var count = 1
        var priorResponse = response.priorResponse
        while (priorResponse != null) {
            count++
            priorResponse = priorResponse.priorResponse
        }
        return count
    }
}