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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class WidgetLunchActivity : AppCompatActivity() {
    
    private var accessToken: String = ""
    
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var buttonLayout: LinearLayout
    private lateinit var endTimeText: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d("WidgetLunchActivity", "Activity started!")
        
        setContentView(R.layout.dialog_lunch)
        
        // Get access token
        val prefs = getSharedPreferences("evohome_prefs", MODE_PRIVATE)
        accessToken = prefs.getString("access_token", "") ?: ""
        
        // Get views
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        buttonLayout = findViewById(R.id.buttonLayout)
        endTimeText = findViewById(R.id.endTimeText)
        
        val cancelButton = findViewById<Button>(R.id.cancelButton)
        val confirmButton = findViewById<Button>(R.id.confirmButton)
        
        // Calculate and show end time
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.MINUTE, 45)
        val endTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(calendar.time)
        endTimeText.text = "Until $endTime"
        
        cancelButton.setOnClickListener {
            finish()
        }
        
        confirmButton.setOnClickListener {
            setLunchMode()
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
    
    private fun setLunchMode() {
        lifecycleScope.launch {
            try {
                // Show progress
                showProgress(true, "Setting lunch mode...")
                
                // Load zones data to find Kitchen
                val prefs = getSharedPreferences("evohome_prefs", MODE_PRIVATE)
                val zonesJson = prefs.getString("zones_data", "[]") ?: "[]"
                
                val type = object : com.google.gson.reflect.TypeToken<List<com.yourname.evohomecontrol.api.Zone>>() {}.type
                val zones = com.google.gson.Gson().fromJson<List<com.yourname.evohomecontrol.api.Zone>>(zonesJson, type) ?: emptyList()
                
                val kitchenZone = zones.find { it.name.equals("Kitchen", ignoreCase = true) }
                
                if (kitchenZone == null) {
                    progressBar.visibility = View.GONE
                    statusText.text = "✗ Kitchen zone not found"
                    statusText.visibility = View.VISIBLE
                    delay(2000)
                    finish()
                    return@launch
                }
                
                // Calculate end time (45 minutes from now)
                val calendar = Calendar.getInstance()
                calendar.add(Calendar.MINUTE, 45)
                val timeUntil = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(calendar.time)
                
                val setpoint = HeatSetpoint(
                    HeatSetpointValue = 19.5,
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
                    } else {
                        progressBar.visibility = View.GONE
                        statusText.text = "✗ Session expired"
                        statusText.visibility = View.VISIBLE
                        delay(2000)
                        finish()
                        return@launch
                    }
                }
                
                if (response.isSuccessful) {
                    // Show success
                    progressBar.visibility = View.GONE
                    statusText.text = "✓ Lunch mode activated"
                    statusText.visibility = View.VISIBLE
                    
                    // Trigger delayed widget refresh
                    val intent = Intent(this@WidgetLunchActivity, Evohome4x2Widget::class.java).apply {
                        action = Evohome4x2Widget.ACTION_DELAYED_REFRESH
                    }
                    sendBroadcast(intent)
                    
                    delay(1500)
                    finish()
                } else {
                    progressBar.visibility = View.GONE
                    statusText.text = "✗ Failed: ${response.code()}"
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
                
                Log.d("WidgetLunchActivity", "Token refreshed successfully")
                true
            } else {
                Log.e("WidgetLunchActivity", "Token refresh failed: ${response.code()}")
                false
            }
        } catch (e: Exception) {
            Log.e("WidgetLunchActivity", "Token refresh error: ${e.message}")
            false
        }
    }
}
