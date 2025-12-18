package com.yourname.evohomecontrol.api

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object EvohomeApiClient {
    
    private const val BASE_URL = "https://tccna.honeywell.com/"
    private const val BASIC_AUTH = "Basic YjAxM2FhMjYtOTcyNC00ZGJkLTg4OTctMDQ4YjlhYWRhMjQ5OnRlc3Q="
    
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }
    
    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val request = if (original.url.encodedPath.contains("Auth/OAuth/Token")) {
            original.newBuilder()
                .header("Authorization", BASIC_AUTH)
                .header("Cache-Control", "no-store no-cache")
                .header("Pragma", "no-cache")
                .build()
        } else {
            original
        }
        chain.proceed(request)
    }
    
    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .addInterceptor(authInterceptor)
        .build()
    
    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    
    val apiService: EvohomeApiService = retrofit.create(EvohomeApiService::class.java)
}