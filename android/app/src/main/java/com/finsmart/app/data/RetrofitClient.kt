package com.finsmart.app.data

import android.content.Context
import com.finsmart.app.BuildConfig
import com.finsmart.app.data.model.RefreshRequest
import com.finsmart.app.utils.TokenManager
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {
    private const val BASE_URL = BuildConfig.BASE_URL
    private var tokenManager: TokenManager? = null
    private var apiService: ApiService? = null

    fun createApi(context: Context): ApiService {
        if (tokenManager == null) {
            tokenManager = TokenManager(context)
        }

        if (apiService == null) {
            val logging = HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
            }

            val client: OkHttpClient = OkHttpClient.Builder()
                .addInterceptor(logging)
                .addInterceptor { chain ->
                    val token = tokenManager?.getToken()
                    val request = chain.request().newBuilder()
                    if (token != null) {
                        request.addHeader("Authorization", "Bearer $token")
                    }
                    chain.proceed(request.build())
                }
                .authenticator(object : Authenticator {
                    override fun authenticate(route: Route?, response: Response): Request? {
                        if (response.request.header("Authorization") != null && responseCount(response) >= 2) {
                            return null
                        }

                        val refreshToken = tokenManager?.getRefreshToken() ?: return null

                        val refreshResponse = runBlocking {
                            apiService?.refreshToken(RefreshRequest(refreshToken))
                        }

                        if (refreshResponse != null && refreshResponse.isSuccessful && refreshResponse.body() != null) {
                            val newTokens = refreshResponse.body()!!
                            tokenManager?.saveToken(newTokens.accessToken)
                            tokenManager?.saveRefreshToken(newTokens.refreshToken)

                            return response.request.newBuilder()
                                .header("Authorization", "Bearer ${newTokens.accessToken}")
                                .build()
                        } else {
                            tokenManager?.clearToken()
                            return null
                        }
                    }
                })
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            apiService = retrofit.create(ApiService::class.java)
        }

        return apiService!!
    }

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