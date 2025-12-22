package com.yourname.evohomecontrol.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.yourname.evohomecontrol.api.EvohomeApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WidgetUpdateWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d("WidgetUpdateWorker", "Starting background data fetch...")
            
            val prefs = context.getSharedPreferences("evohome_prefs", Context.MODE_PRIVATE)
            var accessToken = prefs.getString("access_token", "") ?: ""
            val userId = prefs.getString("user_id", "") ?: ""
            
            if (accessToken.isEmpty() || userId.isEmpty()) {
                Log.e("WidgetUpdateWorker", "No credentials found")
                return@withContext Result.failure()
            }
            
            // Get installations
            var installationsResponse = EvohomeApiClient.apiService.getInstallations(
                userId = userId,
                auth = "bearer $accessToken"
            )
            
            // Handle token refresh if needed
            if (installationsResponse.code() == 401) {
                Log.d("WidgetUpdateWorker", "Token expired, attempting refresh...")
                if (refreshAccessToken(context)) {
                    accessToken = prefs.getString("access_token", "") ?: ""
                    installationsResponse = EvohomeApiClient.apiService.getInstallations(
                        userId = userId,
                        auth = "bearer $accessToken"
                    )
                } else {
                    Log.e("WidgetUpdateWorker", "Token refresh failed")
                    return@withContext Result.failure()
                }
            }
            
            if (!installationsResponse.isSuccessful || installationsResponse.body() == null) {
                Log.e("WidgetUpdateWorker", "Failed to get installations: ${installationsResponse.code()}")
                return@withContext Result.failure()
            }
            
            val installations = installationsResponse.body()!!
            if (installations.isEmpty()) {
                Log.e("WidgetUpdateWorker", "No installations found")
                return@withContext Result.failure()
            }
            
            val locationId = installations[0].locationInfo.locationId
            
            // Get location status
            var statusResponse = EvohomeApiClient.apiService.getLocationStatus(
                locationId = locationId,
                auth = "bearer $accessToken"
            )
            
            if (statusResponse.code() == 401) {
                if (refreshAccessToken(context)) {
                    accessToken = prefs.getString("access_token", "") ?: ""
                    statusResponse = EvohomeApiClient.apiService.getLocationStatus(
                        locationId = locationId,
                        auth = "bearer $accessToken"
                    )
                } else {
                    return@withContext Result.failure()
                }
            }
            
            if (!statusResponse.isSuccessful || statusResponse.body() == null) {
                Log.e("WidgetUpdateWorker", "Failed to get status: ${statusResponse.code()}")
                return@withContext Result.failure()
            }
            
            val installation = statusResponse.body()!!
            val allZones = installation.gateways.flatMap { gateway ->
                gateway.temperatureControlSystems.flatMap { system ->
                    system.zones
                }
            }
            
            // Save zones data
            val zonesJson = Gson().toJson(allZones)
            prefs.edit().putString("zones_data", zonesJson).apply()
            
            Log.d("WidgetUpdateWorker", "Successfully fetched and saved ${allZones.size} zones")
            
            // Trigger widget update
            val intent = Intent(context, Evohome4x2Widget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            }
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(
                ComponentName(context, Evohome4x2Widget::class.java)
            )
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            context.sendBroadcast(intent)
            
            Result.success()
        } catch (e: Exception) {
            Log.e("WidgetUpdateWorker", "Error fetching data: ${e.message}", e)
            Result.failure()
        }
    }
    
    private suspend fun refreshAccessToken(context: Context): Boolean {
        return try {
            val prefs = context.getSharedPreferences("evohome_prefs", Context.MODE_PRIVATE)
            val refreshToken = prefs.getString("refresh_token", "") ?: ""
            val email = prefs.getString("saved_email", "") ?: ""
            val password = prefs.getString("saved_password", "") ?: ""
            
            if (refreshToken.isEmpty() && (email.isEmpty() || password.isEmpty())) {
                return false
            }
            
            val response = if (refreshToken.isNotEmpty()) {
                EvohomeApiClient.apiService.refreshToken(
                    grantType = "refresh_token",
                    refreshToken = refreshToken
                )
            } else {
                EvohomeApiClient.apiService.getTokens(
                    grantType = "password",
                    username = email,
                    password = password
                )
            }
            
            if (response.isSuccessful && response.body() != null) {
                val tokenResponse = response.body()!!
                prefs.edit().apply {
                    putString("access_token", tokenResponse.access_token)
                    putString("refresh_token", tokenResponse.refresh_token)
                    apply()
                }
                Log.d("WidgetUpdateWorker", "Token refreshed successfully")
                true
            } else {
                Log.e("WidgetUpdateWorker", "Token refresh failed: ${response.code()}")
                false
            }
        } catch (e: Exception) {
            Log.e("WidgetUpdateWorker", "Token refresh error: ${e.message}")
            false
        }
    }
}