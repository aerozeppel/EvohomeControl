package com.yourname.evohomecontrol

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yourname.evohomecontrol.api.EvohomeApiClient
import com.yourname.evohomecontrol.api.HeatSetpoint
import com.yourname.evohomecontrol.widget.Evohome4x2Widget
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class WidgetAwayModeActivity : AppCompatActivity() {
    
    private var currentDurationMinutes = 60
    private var accessToken: String = ""
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_widget_away_mode)
        
        Log.d("WidgetAwayModeActivity", "Activity started!")
        
        // Get access token
        val prefs = getSharedPreferences("evohome_prefs", MODE_PRIVATE)
        accessToken = prefs.getString("access_token", "") ?: ""
        
        // Setup UI
        val durationDisplay = findViewById<TextView>(R.id.durationDisplay)
        val durationSubtext = findViewById<TextView>(R.id.durationSubtext)
        val durationMinus = findViewById<Button>(R.id.durationMinus)
        val durationPlus = findViewById<Button>(R.id.durationPlus)
        val duration1h = findViewById<Button>(R.id.duration1h)
        val duration4h = findViewById<Button>(R.id.duration4h)
        val duration8h = findViewById<Button>(R.id.duration8h)
        val duration24h = findViewById<Button>(R.id.duration24h)
        val cancelButton = findViewById<Button>(R.id.cancelButton)
        val enableButton = findViewById<Button>(R.id.enableButton)
        
        fun updateDurationDisplay() {
            val totalMinutes = currentDurationMinutes
            val days = totalMinutes / 1440
            val hours = (totalMinutes % 1440) / 60
            val minutes = totalMinutes % 60

            durationDisplay.text = when {
                days > 0 && hours > 0 && minutes > 0 -> "${days}d ${hours}h ${minutes}m"
                days > 0 && hours > 0 -> "${days}d ${hours}h"
                days > 0 && minutes > 0 -> "${days}d ${minutes}m"
                days > 0 -> "${days}d"
                hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
                hours > 0 -> "${hours}h"
                else -> "${minutes}m"
            }

            val calendar = Calendar.getInstance()
            calendar.add(Calendar.MINUTE, currentDurationMinutes)
            val endTime = SimpleDateFormat("HH:mm", Locale.US).format(calendar.time)
            val endDate = SimpleDateFormat("MMM dd", Locale.US).format(calendar.time)

            durationSubtext.text = if (currentDurationMinutes >= 1440) {
                "Until $endDate at $endTime"
            } else {
                "Until $endTime"
            }
        }
        
        // Duration controls
        durationMinus.setOnClickListener {
            if (currentDurationMinutes > 30) {
                currentDurationMinutes -= 30
                updateDurationDisplay()
            }
        }
        
        durationPlus.setOnClickListener {
            if (currentDurationMinutes < 10080) {
                currentDurationMinutes += 30
                updateDurationDisplay()
            }
        }
        
        // Quick duration buttons
        duration1h.setOnClickListener {
            currentDurationMinutes = 60
            updateDurationDisplay()
        }
        
        duration4h.setOnClickListener {
            currentDurationMinutes = 240
            updateDurationDisplay()
        }
        
        duration8h.setOnClickListener {
            currentDurationMinutes = 480
            updateDurationDisplay()
        }
        
        duration24h.setOnClickListener {
            currentDurationMinutes = 1440
            updateDurationDisplay()
        }
        
        // Cancel button
        cancelButton.setOnClickListener {
            finish()
        }
        
        // Enable button
        enableButton.setOnClickListener {
            enableAwayMode()
        }
        
        // Initialize display
        updateDurationDisplay()
        
        // Make dialog-style
        setFinishOnTouchOutside(true)
    }
    
    override fun finish() {
        super.finish()
        finishAffinity()
    }
    
    private fun enableAwayMode() {
        lifecycleScope.launch {
            try {
                // Get zones
                val prefs = getSharedPreferences("evohome_prefs", MODE_PRIVATE)
                val zonesJson = prefs.getString("zones_data", "[]") ?: "[]"
                val type = object : com.google.gson.reflect.TypeToken<List<com.yourname.evohomecontrol.api.Zone>>() {}.type
                val allZones = com.google.gson.Gson().fromJson<List<com.yourname.evohomecontrol.api.Zone>>(zonesJson, type) ?: emptyList()
                
                if (allZones.isEmpty()) {
                    findViewById<TextView>(R.id.dialogTitle).text = "No zones found"
                    return@launch
                }
                
                // Calculate end time
                val calendar = Calendar.getInstance()
                calendar.add(Calendar.MINUTE, currentDurationMinutes)
                val timeUntil = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(calendar.time)
                
                // Set all zones
                val tasks = allZones.map { zone ->
                    async {
                        try {
                            val targetTemp = if (zone.name.equals("Kitchen", ignoreCase = true)) {
                                17.5
                            } else {
                                18.5
                            }
                            
                            val setpoint = HeatSetpoint(
                                HeatSetpointValue = targetTemp,
                                SetpointMode = 2,
                                TimeUntil = timeUntil
                            )
                            
                            var response = EvohomeApiClient.apiService.setTemperature(
                                zoneId = zone.zoneId,
                                auth = "bearer $accessToken",
                                setpoint = setpoint
                            )
                            
                            if (response.code() == 401) {
                                if (refreshAccessToken()) {
                                    response = EvohomeApiClient.apiService.setTemperature(
                                        zoneId = zone.zoneId,
                                        auth = "bearer $accessToken",
                                        setpoint = setpoint
                                    )
                                }
                            }
                            
                            response.isSuccessful
                        } catch (e: Exception) {
                            Log.e("WidgetAwayMode", "Error setting ${zone.name}: ${e.message}")
                            false
                        }
                    }
                }
                
                val results = tasks.map { it.await() }
                val successCount = results.count { it }
                
                if (successCount > 0) {
                    // Trigger delayed widget refresh (20 seconds)
                    val intent = Intent(this@WidgetAwayModeActivity, Evohome4x2Widget::class.java).apply {
                        action = Evohome4x2Widget.ACTION_DELAYED_REFRESH
                    }
                    sendBroadcast(intent)
                    
                    finish()
                } else {
                    findViewById<TextView>(R.id.dialogTitle).text = "Failed to enable"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                findViewById<TextView>(R.id.dialogTitle).text = "Error: ${e.message}"
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