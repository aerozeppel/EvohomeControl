package com.yourname.evohomecontrol

import android.appwidget.AppWidgetManager
import android.content.ComponentName
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class WidgetZoneEditActivity : AppCompatActivity() {
    
    private var selectedTemp: Double = 20.0
    private var currentDurationMinutes = 60
    private var isPermanent = false
    private var zoneId: String = ""
    private var zoneName: String = ""
    private var currentTemp: Double = 0.0
    private var targetTemp: Double = 0.0
    private var accessToken: String = ""
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d("WidgetZoneEditActivity", "Activity started!")
        
        setContentView(R.layout.dialog_widget_zone_edit)
        
        // Get zone info from intent
        zoneId = intent.getStringExtra("ZONE_ID") ?: ""
        zoneName = intent.getStringExtra("ZONE_NAME") ?: ""
        currentTemp = intent.getDoubleExtra("CURRENT_TEMP", 20.0)
        targetTemp = intent.getDoubleExtra("TARGET_TEMP", 20.0)
        
        // Set initial selected temp to current target
        selectedTemp = targetTemp
        
        Log.d("WidgetZoneEditActivity", "Zone: $zoneName, Current: $currentTemp, Target: $targetTemp")
        
        // Get access token
        val prefs = getSharedPreferences("evohome_prefs", MODE_PRIVATE)
        accessToken = prefs.getString("access_token", "") ?: ""
        
        // Setup UI
        findViewById<TextView>(R.id.dialogTitle).text = zoneName
        findViewById<TextView>(R.id.currentStatus).text = "Current: ${String.format("%.1f", currentTemp)}°C → Target: ${String.format("%.1f", targetTemp)}°C"
        
        val tempDisplay = findViewById<TextView>(R.id.tempDisplay)
        val durationDisplay = findViewById<TextView>(R.id.durationDisplay)
        val durationSubtext = findViewById<TextView>(R.id.durationSubtext)
        
        val tempMinus = findViewById<Button>(R.id.tempMinus)
        val tempPlus = findViewById<Button>(R.id.tempPlus)
        val durationMinus = findViewById<Button>(R.id.durationMinus)
        val durationPlus = findViewById<Button>(R.id.durationPlus)
        
        val duration1h = findViewById<Button>(R.id.duration1h)
        val duration2h = findViewById<Button>(R.id.duration2h)
        val duration4h = findViewById<Button>(R.id.duration4h)
        val durationPermanent = findViewById<Button>(R.id.durationPermanent)
        
        val followScheduleButton = findViewById<Button>(R.id.followScheduleButton)
        val applyButton = findViewById<Button>(R.id.applyButton)
        
        fun updateTempDisplay() {
            tempDisplay.text = "${String.format("%.1f", selectedTemp)}°C"
        }
        
        fun updateDurationDisplay() {
            if (isPermanent) {
                durationDisplay.text = "Permanent"
                durationSubtext.text = "Until manually changed"
            } else {
                if (currentDurationMinutes >= 1440) {
                    val days = currentDurationMinutes / 1440
                    durationDisplay.text = "$days days"
                } else {
                    val hours = currentDurationMinutes / 60
                    val minutes = currentDurationMinutes % 60

                    durationDisplay.text = when {
                        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
                        hours > 0 -> "${hours}h"
                        else -> "${minutes}m"
                    }
                }

                val calendar = Calendar.getInstance()
                calendar.add(Calendar.MINUTE, currentDurationMinutes)
                val endTime = SimpleDateFormat("HH:mm", Locale.US).format(calendar.time)
                durationSubtext.text = "Until $endTime"
            }
        }
        
        // Temperature controls
        tempMinus.setOnClickListener {
            if (selectedTemp > 5.0) {
                selectedTemp -= 0.5
                updateTempDisplay()
            }
        }
        
        tempPlus.setOnClickListener {
            if (selectedTemp < 35.0) {
                selectedTemp += 0.5
                updateTempDisplay()
            }
        }
        
        // Duration controls
        durationMinus.setOnClickListener {
            if (!isPermanent && currentDurationMinutes > 15) {
                val step = when {
                    currentDurationMinutes <= 60 -> 15
                    currentDurationMinutes <= 180 -> 30
                    currentDurationMinutes <= 720 -> 60
                    currentDurationMinutes <= 1440 -> 720
                    else -> 1440
                }
                currentDurationMinutes -= step
                updateDurationDisplay()
            }
        }
        
        durationPlus.setOnClickListener {
            if (!isPermanent) {
                val step = when {
                    currentDurationMinutes < 60 -> 15
                    currentDurationMinutes < 180 -> 30
                    currentDurationMinutes < 720 -> 60
                    currentDurationMinutes < 1440 -> 720
                    else -> 1440
                }
                if (currentDurationMinutes + step <= 10080) {
                    currentDurationMinutes += step
                    updateDurationDisplay()
                }
            }
        }
        
        // Quick duration buttons
        duration1h.setOnClickListener {
            isPermanent = false
            currentDurationMinutes = 60
            updateDurationDisplay()
        }
        
        duration2h.setOnClickListener {
            isPermanent = false
            currentDurationMinutes = 120
            updateDurationDisplay()
        }
        
        duration4h.setOnClickListener {
            isPermanent = false
            currentDurationMinutes = 240
            updateDurationDisplay()
        }
        
        durationPermanent.setOnClickListener {
            isPermanent = true
            updateDurationDisplay()
        }
        
        // Follow Schedule button
        followScheduleButton.setOnClickListener {
            cancelOverride()
        }
        
        // Apply button
        applyButton.setOnClickListener {
            applyTempOverride()
        }
        
        // Initialize displays
        updateTempDisplay()
        updateDurationDisplay()
        
        // Make dialog-style
        setFinishOnTouchOutside(true)
    }
    
    private fun applyTempOverride() {
        lifecycleScope.launch {
            try {
                val setpoint = if (isPermanent) {
                    HeatSetpoint(
                        HeatSetpointValue = selectedTemp,
                        SetpointMode = 1,
                        TimeUntil = null
                    )
                } else {
                    val calendar = Calendar.getInstance()
                    calendar.add(Calendar.MINUTE, currentDurationMinutes)
                    val timeUntil = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }.format(calendar.time)

                    HeatSetpoint(
                        HeatSetpointValue = selectedTemp,
                        SetpointMode = 2,
                        TimeUntil = timeUntil
                    )
                }

                var response = EvohomeApiClient.apiService.setTemperature(
                    zoneId = zoneId,
                    auth = "bearer $accessToken",
                    setpoint = setpoint
                )

                if (response.code() == 401) {
                    Log.d("WidgetZoneEditActivity", "Token expired, attempting refresh...")
                    if (refreshAccessToken()) {
                        response = EvohomeApiClient.apiService.setTemperature(
                            zoneId = zoneId,
                            auth = "bearer $accessToken",
                            setpoint = setpoint
                        )
                    } else {
                        findViewById<TextView>(R.id.dialogTitle).text = "Session Expired"
                        return@launch
                    }
                }

                if (response.isSuccessful) {
                    // Update widget
                    val intent = Intent(this@WidgetZoneEditActivity, Evohome4x2Widget::class.java).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    }
                    val ids = AppWidgetManager.getInstance(application).getAppWidgetIds(
                        ComponentName(application, Evohome4x2Widget::class.java)
                    )
                    intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    sendBroadcast(intent)
                    
                    setResult(RESULT_OK)
                    finish()
                } else {
                    findViewById<TextView>(R.id.dialogTitle).text = "Failed: ${response.code()}"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                findViewById<TextView>(R.id.dialogTitle).text = "Error: ${e.message}"
            }
        }
    }
    
    private fun cancelOverride() {
        lifecycleScope.launch {
            try {
                val setpoint = HeatSetpoint(
                    HeatSetpointValue = 0.0,
                    SetpointMode = 0,
                    TimeUntil = null
                )

                var response = EvohomeApiClient.apiService.setTemperature(
                    zoneId = zoneId,
                    auth = "bearer $accessToken",
                    setpoint = setpoint
                )

                if (response.code() == 401) {
                    if (refreshAccessToken()) {
                        response = EvohomeApiClient.apiService.setTemperature(
                            zoneId = zoneId,
                            auth = "bearer $accessToken",
                            setpoint = setpoint
                        )
                    } else {
                        findViewById<TextView>(R.id.dialogTitle).text = "Session Expired"
                        return@launch
                    }
                }

                if (response.isSuccessful) {
                    val intent = Intent(this@WidgetZoneEditActivity, Evohome4x2Widget::class.java).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    }
                    val ids = AppWidgetManager.getInstance(application).getAppWidgetIds(
                        ComponentName(application, Evohome4x2Widget::class.java)
                    )
                    intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    sendBroadcast(intent)
                    
                    setResult(RESULT_OK)
                    finish()
                } else {
                    findViewById<TextView>(R.id.dialogTitle).text = "Failed: ${response.code()}"
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
                
                Log.d("WidgetZoneEditActivity", "Token refreshed successfully")
                true
            } else {
                Log.e("WidgetZoneEditActivity", "Token refresh failed: ${response.code()}")
                false
            }
        } catch (e: Exception) {
            Log.e("WidgetZoneEditActivity", "Token refresh error: ${e.message}")
            false
        }
    }
}
