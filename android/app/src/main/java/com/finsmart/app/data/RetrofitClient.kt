package com.finsmart.app.data

import android.content.Context
import com.finsmart.app.utils.TokenManager
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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
                // Блокирующий вызов, чтобы гарантированно получить токен
                val token = runBlocking { tokenManager?.getToken() }
                android.util.Log.d("FIN_TOKEN", "Полученный токен: $token")
                val request = chain.request().newBuilder()
                if (token != null) {
                    request.addHeader("Authorization", "Bearer $token")
                }
                chain.proceed(request.build())
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}