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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.yourname.evohomecontrol.api.EvohomeApiClient
import com.yourname.evohomecontrol.api.HeatSetpoint
import com.yourname.evohomecontrol.api.Zone
import com.yourname.evohomecontrol.widget.EvohomeWidget
import com.yourname.evohomecontrol.R
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class WidgetBoostActivity : AppCompatActivity() {
    
    private var selectedTemp: Double = 20.0
    private var zoneId: String = ""
    private var zoneName: String = ""
    private var targetTemp: Double = 0.0
    private var accessToken: String = ""
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        android.util.Log.d("WidgetBoostActivity", "Activity started!")
        
        setContentView(R.layout.dialog_widget_boost)
        
        // Get zone info from intent
        zoneId = intent.getStringExtra("ZONE_ID") ?: ""
        zoneName = intent.getStringExtra("ZONE_NAME") ?: ""
        targetTemp = intent.getDoubleExtra("TARGET_TEMP", 20.0)
        
        // Set initial selected temp to current target
        selectedTemp = targetTemp
        
        android.util.Log.d("WidgetBoostActivity", "Zone: $zoneName, Target: $targetTemp")
        
        // Get access token
        val prefs = getSharedPreferences("evohome_prefs", MODE_PRIVATE)
        accessToken = prefs.getString("access_token", "") ?: ""
        
        // Setup UI
        findViewById<TextView>(R.id.dialogZoneName).text = zoneName
        val tempDisplayView = findViewById<TextView>(R.id.tempOffset)
        val btnMinus = findViewById<Button>(R.id.btnMinus)
        val btnPlus = findViewById<Button>(R.id.btnPlus)
        val btnCancel = findViewById<Button>(R.id.btnCancel)
        val btnBoost = findViewById<Button>(R.id.btnBoost)
        
        // Update label
        findViewById<TextView>(R.id.offsetLabel).text = "Target Temperature"
        
        updateTempDisplay(tempDisplayView)
        
        // Minus button (min 5.0°C)
        btnMinus.setOnClickListener {
            if (selectedTemp > 5.0) {
                selectedTemp -= 0.5
                updateTempDisplay(tempDisplayView)
            }
        }
        
        // Plus button (max 35.0°C)
        btnPlus.setOnClickListener {
            if (selectedTemp < 35.0) {
                selectedTemp += 0.5
                updateTempDisplay(tempDisplayView)
            }
        }
        
        // Cancel button
        btnCancel.setOnClickListener {
            finish()
        }
        
        // Boost button
        btnBoost.setOnClickListener {
            applyBoost()
        }
        
        // Make dialog-style
        setFinishOnTouchOutside(true)
    }
    
    private fun updateTempDisplay(textView: TextView) {
        textView.text = "${String.format("%.1f", selectedTemp)}°C"
        
        // Update color based on temperature
        val color = when {
            selectedTemp < 16.0 -> 0xFF2196F3.toInt() // Blue - cool
            selectedTemp < 20.0 -> 0xFF4CAF50.toInt() // Green - comfortable
            selectedTemp < 23.0 -> 0xFFFFA726.toInt() // Orange - warm
            else -> 0xFFFF5722.toInt() // Red - hot
        }
        textView.setTextColor(color)
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
                
                Log.d("WidgetBoostActivity", "Token refreshed successfully")
                true
            } else {
                Log.e("WidgetBoostActivity", "Token refresh failed: ${response.code()}")
                false
            }
        } catch (e: Exception) {
            Log.e("WidgetBoostActivity", "Token refresh error: ${e.message}")
            false
        }
    }
    
    private fun applyBoost() {
        lifecycleScope.launch {
            try {
                val calendar = Calendar.getInstance()
                calendar.add(Calendar.HOUR_OF_DAY, 1)
                val timeUntil = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(calendar.time)

                val setpoint = HeatSetpoint(
                    HeatSetpointValue = selectedTemp,
                    SetpointMode = 2,
                    TimeUntil = timeUntil
                )

                var response = EvohomeApiClient.apiService.setTemperature(
                    zoneId = zoneId,
                    auth = "bearer $accessToken",
                    setpoint = setpoint
                )

                // If unauthorized, try to refresh token
                if (response.code() == 401) {
                    Log.d("WidgetBoostActivity", "Token expired, attempting refresh...")
                    if (refreshAccessToken()) {
                        // Retry with new token
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
                    val intent = Intent(this@WidgetBoostActivity, EvohomeWidget::class.java).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    }
                    val ids = AppWidgetManager.getInstance(application).getAppWidgetIds(
                        ComponentName(application, EvohomeWidget::class.java)
                    )
                    intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    sendBroadcast(intent)
                    
                    // Show success and finish
                    setResult(RESULT_OK)
                    finish()
                } else {
                    // Show error
                    findViewById<TextView>(R.id.dialogTitle).text = "Boost Failed: ${response.code()}"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                findViewById<TextView>(R.id.dialogTitle).text = "Error: ${e.message}"
            }
        }
    }
}