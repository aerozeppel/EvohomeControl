package com.yourname.evohomecontrol

import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yourname.evohomecontrol.api.EvohomeApiClient
import com.yourname.evohomecontrol.api.HeatSetpoint
import com.yourname.evohomecontrol.widget.Evohome4x2Widget
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class WidgetWorkFromHomeActivity : AppCompatActivity() {
    
    private var accessToken: String = ""
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d("WidgetWorkFromHome", "Activity started!")
        
        // Get access token
        val prefs = getSharedPreferences("evohome_prefs", MODE_PRIVATE)
        accessToken = prefs.getString("access_token", "") ?: ""
        
        // Show time picker immediately
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(Calendar.MINUTE)
        
        val timePickerDialog = TimePickerDialog(
            this,
            { _, hour, minute ->
                setWorkFromHome(hour, minute)
            },
            currentHour,
            currentMinute,
            true
        )
        
        timePickerDialog.setOnCancelListener {
            finish()
        }
        
        timePickerDialog.show()
    }
    
    override fun finish() {
        super.finish()
        finishAffinity()
    }
    
    private fun setWorkFromHome(hour: Int, minute: Int) {
        lifecycleScope.launch {
            try {
                // Get zones
                val prefs = getSharedPreferences("evohome_prefs", MODE_PRIVATE)
                val zonesJson = prefs.getString("zones_data", "[]") ?: "[]"
                val type = object : com.google.gson.reflect.TypeToken<List<com.yourname.evohomecontrol.api.Zone>>() {}.type
                val allZones = com.google.gson.Gson().fromJson<List<com.yourname.evohomecontrol.api.Zone>>(zonesJson, type) ?: emptyList()
                
                val kitchenZone = allZones.find { it.name.equals("Kitchen", ignoreCase = true) }
                
                if (kitchenZone == null) {
                    Log.e("WidgetWorkFromHome", "Kitchen zone not found")
                    finish()
                    return@launch
                }
                
                // Calculate end time
                val endTime = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    
                    if (before(Calendar.getInstance())) {
                        add(Calendar.DAY_OF_MONTH, 1)
                    }
                }
                
                val timeUntil = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(endTime.time)
                
                val setpoint = HeatSetpoint(
                    HeatSetpointValue = 18.5,
                    SetpointMode = 2,
                    TimeUntil = timeUntil
                )
                
                var response = EvohomeApiClient.apiService.setTemperature(
                    zoneId = kitchenZone.zoneId,
                    auth = "bearer $accessToken",
                    setpoint = setpoint
                )
                
                if (response.code() == 401) {
                    if (refreshAccessToken()) {
                        response = EvohomeApiClient.apiService.setTemperature(
                            zoneId = kitchenZone.zoneId,
                            auth = "bearer $accessToken",
                            setpoint = setpoint
                        )
                    }
                }
                
                if (response.isSuccessful) {
                    // Trigger delayed widget refresh (20 seconds)
                    val intent = Intent(this@WidgetWorkFromHomeActivity, Evohome4x2Widget::class.java).apply {
                        action = Evohome4x2Widget.ACTION_DELAYED_REFRESH
                    }
                    sendBroadcast(intent)
                }
                
                finish()
            } catch (e: Exception) {
                e.printStackTrace()
                finish()
            }
        }
    }
    
    private suspend fun refreshAccessToken(): Boolean {
        return try {
            val prefs = getSharedPreferences("evohome_prefs", MODE_PRIVATE)
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
                accessToken = tokenResponse.access_token
                
                prefs.edit().apply {
                    putString("access_token", tokenResponse.access_token)
                    putString("refresh_token", tokenResponse.refresh_token)
                    apply()
                }
                
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}