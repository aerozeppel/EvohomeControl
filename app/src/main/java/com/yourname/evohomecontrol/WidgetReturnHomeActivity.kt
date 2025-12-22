package com.yourname.evohomecontrol

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yourname.evohomecontrol.api.EvohomeApiClient
import com.yourname.evohomecontrol.api.HeatSetpoint
import com.yourname.evohomecontrol.widget.Evohome4x2Widget
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WidgetReturnHomeActivity : AppCompatActivity() {
    
    private var accessToken: String = ""
    
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var buttonLayout: LinearLayout
    private lateinit var zonesCount: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d("WidgetReturnHomeActivity", "Activity started!")
        
        setContentView(R.layout.dialog_return_home)
        
        // Get access token
        val prefs = getSharedPreferences("evohome_prefs", MODE_PRIVATE)
        accessToken = prefs.getString("access_token", "") ?: ""
        
        // Get views
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        buttonLayout = findViewById(R.id.buttonLayout)
        zonesCount = findViewById(R.id.zonesCount)
        
        val cancelButton = findViewById<Button>(R.id.cancelButton)
        val confirmButton = findViewById<Button>(R.id.confirmButton)
        
        // Load zones to show count
        val zonesJson = prefs.getString("zones_data", "[]") ?: "[]"
        val type = object : com.google.gson.reflect.TypeToken<List<com.yourname.evohomecontrol.api.Zone>>() {}.type
        val zones = com.google.gson.Gson().fromJson<List<com.yourname.evohomecontrol.api.Zone>>(zonesJson, type) ?: emptyList()
        
        val zonesWithOverrides = zones.filter { 
            it.heatSetpointStatus.setpointMode != "FollowSchedule" 
        }
        
        if (zonesWithOverrides.isNotEmpty()) {
            zonesCount.text = "${zonesWithOverrides.size} zone${if (zonesWithOverrides.size != 1) "s" else ""} with active overrides"
            zonesCount.visibility = View.VISIBLE
        }
        
        cancelButton.setOnClickListener {
            finish()
        }
        
        confirmButton.setOnClickListener {
            cancelAllOverrides()
        }
        
        // Make dialog-style
        setFinishOnTouchOutside(true)
    }
    
    override fun finish() {
        super.finish()
        finishAffinity()
    }
    
    private fun showProgress(show: Boolean, message: String = "") {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
        statusText.visibility = if (show && message.isNotEmpty()) View.VISIBLE else View.GONE
        statusText.text = message
        buttonLayout.visibility = if (show) View.GONE else View.VISIBLE
    }
    
    private fun cancelAllOverrides() {
        lifecycleScope.launch {
            try {
                // Show progress
                showProgress(true, "Cancelling overrides...")
                
                // Load zones data
                val prefs = getSharedPreferences("evohome_prefs", MODE_PRIVATE)
                val zonesJson = prefs.getString("zones_data", "[]") ?: "[]"
                
                val type = object : com.google.gson.reflect.TypeToken<List<com.yourname.evohomecontrol.api.Zone>>() {}.type
                val zones = com.google.gson.Gson().fromJson<List<com.yourname.evohomecontrol.api.Zone>>(zonesJson, type) ?: emptyList()
                
                if (zones.isEmpty()) {
                    statusText.text = "No zones found"
                    delay(1500)
                    finish()
                    return@launch
                }
                
                // Cancel all overrides
                val tasks = zones.map { zone ->
                    async {
                        try {
                            val setpoint = HeatSetpoint(
                                HeatSetpointValue = 0.0,
                                SetpointMode = 0,
                                TimeUntil = null
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
                                } else {
                                    return@async false
                                }
                            }
                            
                            response.isSuccessful
                        } catch (e: Exception) {
                            Log.e("WidgetReturnHomeActivity", "Error cancelling ${zone.name}: ${e.message}")
                            false
                        }
                    }
                }
                
                val results = tasks.map { it.await() }
                val successCount = results.count { it }
                
                if (successCount > 0) {
                    // Show success
                    progressBar.visibility = View.GONE
                    statusText.text = "✓ All zones returned to schedule"
                    statusText.visibility = View.VISIBLE
                    
                    // Trigger delayed widget refresh
                    val intent = Intent(this@WidgetReturnHomeActivity, Evohome4x2Widget::class.java).apply {
                        action = Evohome4x2Widget.ACTION_DELAYED_REFRESH
                    }
                    sendBroadcast(intent)
                    
                    delay(1500)
                    finish()
                } else {
                    progressBar.visibility = View.GONE
                    statusText.text = "✗ Failed to cancel overrides"
                    statusText.visibility = View.VISIBLE
                    delay(2000)
                    finish()
                }
                
            } catch (e: Exception) {
                e.printStackTrace()
                progressBar.visibility = View.GONE
                statusText.text = "✗ Error: ${e.message}"
                statusText.visibility = View.VISIBLE
                delay(2000)
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
                
                Log.d("WidgetReturnHomeActivity", "Token refreshed successfully")
                true
            } else {
                Log.e("WidgetReturnHomeActivity", "Token refresh failed: ${response.code()}")
                false
            }
        } catch (e: Exception) {
            Log.e("WidgetReturnHomeActivity", "Token refresh error: ${e.message}")
            false
        }
    }
}
