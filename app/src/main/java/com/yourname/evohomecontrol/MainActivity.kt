package com.yourname.evohomecontrol

import android.app.NotificationChannel
import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import com.yourname.evohomecontrol.api.EvohomeApiClient
import com.yourname.evohomecontrol.api.HeatSetpoint
import com.yourname.evohomecontrol.api.Zone
import com.yourname.evohomecontrol.databinding.ActivityMainBinding
import com.yourname.evohomecontrol.widget.EvohomeWidget
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ZoneAdapter
    private var accessToken: String = ""
    private var userId: String = ""
    private var locationId: String = ""
    private var allZones: List<Zone> = emptyList()
    // Connection & Data Freshness Tracking
    private var lastSuccessfulUpdate: Long = 0
    private var isConnected: Boolean = true
    private var consecutiveFailures: Int = 0
    private var autoRefreshJob: Job? = null
    private val AUTO_REFRESH_INTERVAL = 120_000L // 2 minutes
    private val STALE_DATA_THRESHOLD = 300_000L // 5 minutes
    private val MAX_CONSECUTIVE_FAILURES = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up toolbar
        setSupportActionBar(binding.toolbar)

        // Get credentials
        val prefs = getSharedPreferences("evohome_prefs", MODE_PRIVATE)
        accessToken = prefs.getString("access_token", "") ?: ""
        userId = prefs.getString("user_id", "") ?: ""

        // Set up RecyclerView
        adapter = ZoneAdapter(
            emptyList(),
            onTempClick = { zone -> showTempOverrideDialog(zone) },
            onScheduleClick = { zone -> openScheduleEditor(zone) }
        )
        binding.zonesRecyclerView.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 2)
        binding.zonesRecyclerView.adapter = adapter

        // Set up SwipeRefreshLayout
        binding.swipeRefreshLayout.setOnRefreshListener {
            loadZones(manualRefresh = true)
        }

        // Set up status bar action buttons
        binding.awayButton.setOnClickListener {
            showAwayModeDialog()
        }

        binding.returnHomeButton.setOnClickListener {
            cancelAllOverrides()
        }

        binding.lunchButton.setOnClickListener {
            setLunchMode()
        }

        binding.workFromHomeButton.setOnClickListener {
            showWorkFromHomeDialog()
        }


        // Set up System Mode button

        // Load last update timestamp
        lastSuccessfulUpdate = prefs.getLong("last_update_timestamp", 0)

        // Setup connection status indicator
        setupConnectionStatusBar()
        // Create notification channel
        createNotificationChannel()

        // Initial load
        loadZones()
        // Check for stale data on launch
        checkDataFreshness()

        // Handle widget intents
        handleWidgetIntent(intent)
    }

    private fun handleWidgetIntent(intent: Intent?) {
    if (intent == null) return
    
    val autoClose = intent.getBooleanExtra("AUTO_CLOSE", false)

    // NEW: Handle delayed refresh request from quick actions
    val delayedRefreshSeconds = intent.getIntExtra("DELAYED_REFRESH_SECONDS", 0)
    if (delayedRefreshSeconds > 0) {
        lifecycleScope.launch {
            Log.d("MainActivity", "Scheduling delayed refresh in $delayedRefreshSeconds seconds...")
            delay(delayedRefreshSeconds * 1000L)
            Log.d("MainActivity", "Executing delayed refresh now")
            loadZones(manualRefresh = false)
        }
    }

    when {
        intent.getBooleanExtra("BACKGROUND_REFRESH", false) -> {
            Log.d("MainActivity", "Background refresh requested by widget")
            
            // Check if there's a delayed refresh
            val delayedRefreshSeconds = intent.getIntExtra("DELAYED_REFRESH_SECONDS", 0)
            
            if (delayedRefreshSeconds > 0) {
                // Schedule delayed refresh, then close
                lifecycleScope.launch {
                    Log.d("MainActivity", "Scheduling delayed refresh in $delayedRefreshSeconds seconds...")
                    delay(delayedRefreshSeconds * 1000L)
                    Log.d("MainActivity", "Executing delayed refresh now")
                    loadZones(manualRefresh = false)
                    delay(2000) // Wait for data to be saved
                    finish()
                }
            } else {
                // Immediate refresh
                loadZones(manualRefresh = false)
                lifecycleScope.launch {
                    delay(2000) // Wait for data to be saved
                    finish()
                }
            }
        }
        intent.getBooleanExtra("SHOW_AWAY_DIALOG", false) -> {
            waitForZonesAndExecute { showAwayModeDialog() }
        }
        intent.getBooleanExtra("CANCEL_ALL_OVERRIDES", false) -> {
            waitForZonesAndExecute {
                cancelAllOverridesFromWidget(autoClose)
            }
        }
        intent.getBooleanExtra("SET_LUNCH_MODE", false) -> {
            waitForZonesAndExecute {
                setLunchModeFromWidget(autoClose)
            }
        }
        intent.getBooleanExtra("SHOW_WORK_FROM_HOME_DIALOG", false) -> {
            waitForZonesAndExecute { 
                showWorkFromHomeDialog(autoClose = intent.getBooleanExtra("AUTO_CLOSE", false)) 
            }
        }
        intent.getBooleanExtra("REFRESH_ZONES", false) -> {
            loadZones(manualRefresh = false)
            if (autoClose) {
                lifecycleScope.launch {
                    delay(3000)
                    finish()
                }
            }
        }
        else -> {
            // No widget intent action matched, do nothing
        }
    }
}

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWidgetIntent(intent)
    }

    override fun onResume() {
        super.onResume()

        // If data is stale, refresh immediately instead of waiting
        if (isDataStale() && lastSuccessfulUpdate > 0) {
            Log.d("MainActivity", "Data is stale on resume, refreshing immediately...")
            loadZones(manualRefresh = false)
        }

        // Start auto-refresh when app is in foreground
        startAutoRefresh()
    }

    override fun onPause() {
        super.onPause()
        // Stop auto-refresh when app goes to background
        stopAutoRefresh()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                loadZones(manualRefresh = true)
                true
            }
            R.id.action_cancel_all -> {
                if (isDataFresh()) {
                    cancelAllOverrides()
                } else {
                    showStaleDataWarning()
                }
                true
            }
            R.id.action_preferences -> {
                Snackbar.make(binding.root, "Preferences coming soon", Snackbar.LENGTH_SHORT).show()
                true
            }
            R.id.action_notifications -> {
                Snackbar.make(binding.root, "Notifications coming soon", Snackbar.LENGTH_SHORT).show()
                true
            }
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            R.id.action_logout -> {
                logout()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadZones(manualRefresh: Boolean = false) {
        binding.swipeRefreshLayout.isRefreshing = false

        if (manualRefresh) {
            showLoading(true)
        }

        lifecycleScope.launch {
            try {
                var installationsResponse = EvohomeApiClient.apiService.getInstallations(
                    userId = userId,
                    auth = "bearer $accessToken"
                )

                if (installationsResponse.code() == 401) {
                    Log.d("MainActivity", "Token expired, attempting refresh...")
                    if (refreshAccessToken()) {
                        installationsResponse = EvohomeApiClient.apiService.getInstallations(
                            userId = userId,
                            auth = "bearer $accessToken"
                        )
                    } else {
                        onConnectionFailed("Session expired. Please login again.")
                        logout()
                        return@launch
                    }
                }

                if (installationsResponse.isSuccessful && installationsResponse.body() != null) {
                    val installations = installationsResponse.body()!!

                    if (installations.isEmpty()) {
                        onConnectionFailed("No locations found")
                        return@launch
                    }

                    locationId = installations[0].locationInfo.locationId

                    var statusResponse = EvohomeApiClient.apiService.getLocationStatus(
                        locationId = locationId,
                        auth = "bearer $accessToken"
                    )

                    if (statusResponse.code() == 401) {
                        if (refreshAccessToken()) {
                            statusResponse = EvohomeApiClient.apiService.getLocationStatus(
                                locationId = locationId,
                                auth = "bearer $accessToken"
                            )
                        } else {
                            onConnectionFailed("Session expired. Please login again.")
                            logout()
                            return@launch
                        }
                    }

                    if (statusResponse.isSuccessful && statusResponse.body() != null) {
                        val installation = statusResponse.body()!!

                        allZones = installation.gateways.flatMap { gateway ->
                            gateway.temperatureControlSystems.flatMap { system ->
                                system.zones
                            }
                        }

                        // SUCCESS
                        onConnectionSuccess()
                        adapter.updateZones(allZones)
                        saveZonesForWidget()
                        updateWidget()

                        if (manualRefresh) {
                            Snackbar.make(binding.root, "Updated ${allZones.size} zones", Snackbar.LENGTH_SHORT).show()
                        }
                    } else {
                        onConnectionFailed("Failed to load temperatures: ${statusResponse.code()}")
                    }
                } else {
                    onConnectionFailed("Failed to load locations: ${installationsResponse.code()}")
                }
            } catch (e: Exception) {
                onConnectionFailed("Network error: ${e.message}")
                e.printStackTrace()
            } finally {
                showLoading(false)
            }
        }
    }

private fun openScheduleEditor(zone: Zone) {
    Log.d("MainActivity", "Opening schedule for zone: ${zone.name}, ID: ${zone.zoneId}")
    val intent = Intent(this, ScheduleEditorActivity::class.java).apply {
        putExtra("zone_id", zone.zoneId)  // Changed from "ZONE_ID" to "zone_id"
        putExtra("zone_name", zone.name)   // Changed from "ZONE_NAME" to "zone_name"
    }
    startActivity(intent)
}


    private fun showTempOverrideDialog(zone: Zone) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_temp_override, null)
        val dialog = AlertDialog.Builder(this)
            .setTitle("${zone.name}")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .create()

        // Get views
        val currentStatus = dialogView.findViewById<TextView>(R.id.currentStatus)
        val tempDisplay = dialogView.findViewById<TextView>(R.id.tempDisplay)
        val durationDisplay = dialogView.findViewById<TextView>(R.id.durationDisplay)
        val durationSubtext = dialogView.findViewById<TextView>(R.id.durationSubtext)

        val tempMinus = dialogView.findViewById<Button>(R.id.tempMinus)
        val tempPlus = dialogView.findViewById<Button>(R.id.tempPlus)
        val durationMinus = dialogView.findViewById<Button>(R.id.durationMinus)
        val durationPlus = dialogView.findViewById<Button>(R.id.durationPlus)

        val duration1h = dialogView.findViewById<Button>(R.id.duration1h)
        val duration2h = dialogView.findViewById<Button>(R.id.duration2h)
        val duration4h = dialogView.findViewById<Button>(R.id.duration4h)
        val durationPermanent = dialogView.findViewById<Button>(R.id.durationPermanent)

        val followScheduleButton = dialogView.findViewById<Button>(R.id.followScheduleButton)
        val applyButton = dialogView.findViewById<Button>(R.id.applyButton)

        // State tracking
        var currentTemp = zone.heatSetpointStatus.targetTemperature
        var currentDurationMinutes = 60 // Default 1 hour
        var isPermanent = false

        // Set current status
        currentStatus.text = "Current: ${zone.temperatureStatus.temperature}°C → Target: ${zone.heatSetpointStatus.targetTemperature}°C"

        fun updateTempDisplay() {
            tempDisplay.text = "${currentTemp}°C"
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

                // Calculate end time
                val calendar = Calendar.getInstance()
                calendar.add(Calendar.MINUTE, currentDurationMinutes)
                val endTime = SimpleDateFormat("HH:mm", Locale.US).format(calendar.time)
                durationSubtext.text = "Until $endTime"
            }
        }

        // Temperature controls
        tempMinus.setOnClickListener {
            if (currentTemp > 5.0) {
                currentTemp -= 0.5
                updateTempDisplay()
            }
        }

        tempPlus.setOnClickListener {
            if (currentTemp < 35.0) {
                currentTemp += 0.5
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
                if (currentDurationMinutes + step <= 10080) { // Max 7 days
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
            dialog.dismiss()
            cancelOverride(zone.zoneId)
        }

        // Apply button
        applyButton.setOnClickListener {
            dialog.dismiss()
            applyTempOverride(
                zoneId = zone.zoneId,
                temperature = currentTemp,
                durationMinutes = if (isPermanent) null else currentDurationMinutes,
                zoneName = zone.name
            )
        }

        // Initialize displays
        updateTempDisplay()
        updateDurationDisplay()

        dialog.show()
    }

    private fun applyTempOverride(
        zoneId: String,
        temperature: Double,
        durationMinutes: Int?,
        zoneName: String
    ) {
        lifecycleScope.launch {
            try {
                val setpoint = if (durationMinutes == null) {
                    // Permanent override
                    HeatSetpoint(
                        HeatSetpointValue = temperature,
                        SetpointMode = 1,
                        TimeUntil = null
                    )
                } else {
                    // Temporary override
                    val calendar = Calendar.getInstance()
                    calendar.add(Calendar.MINUTE, durationMinutes)
                    val timeUntil = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }.format(calendar.time)

                    HeatSetpoint(
                        HeatSetpointValue = temperature,
                        SetpointMode = 2,
                        TimeUntil = timeUntil
                    )
                }

                val response = EvohomeApiClient.apiService.setTemperature(
                    zoneId = zoneId,
                    auth = "bearer $accessToken",
                    setpoint = setpoint
                )

                if (response.isSuccessful) {
                    val durationText = if (durationMinutes == null) {
                        "Permanent"
                    } else {
                        val hours = durationMinutes / 60
                        val minutes = durationMinutes % 60
                        when {
                            hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
                            hours > 0 -> "${hours}h"
                            else -> "${minutes}m"
                        }
                    }

                    Snackbar.make(
                        binding.root,
                        "$zoneName: ${temperature}°C for $durationText",
                        Snackbar.LENGTH_LONG
                    ).setAction("Undo") {
                        cancelOverride(zoneId)
                    }.show()

                    delay(2000)
                    loadZones()
                } else {
                    showError("Failed: ${response.code()}")
                }
            } catch (e: Exception) {
                showError("Error: ${e.message}")
                e.printStackTrace()
            }
        }
    }


    private fun cancelOverride(zoneId: String) {
        lifecycleScope.launch {
            try {
                val setpoint = HeatSetpoint(
                    HeatSetpointValue = 0.0,
                    SetpointMode = 0,
                    TimeUntil = null
                )

                val response = EvohomeApiClient.apiService.setTemperature(
                    zoneId = zoneId,
                    auth = "bearer $accessToken",
                    setpoint = setpoint
                )

                if (response.isSuccessful) {
                    Snackbar.make(binding.root, "Now following schedule", Snackbar.LENGTH_SHORT).show()
                    delay(2000)
                    loadZones()
                } else {
                    showError("Failed: ${response.code()}")
                }
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }


    private fun showAwayModeDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_away_mode, null)
        val durationDisplay = dialogView.findViewById<TextView>(R.id.durationDisplay)
        val durationSubtext = dialogView.findViewById<TextView>(R.id.durationSubtext)
        val durationMinus = dialogView.findViewById<Button>(R.id.durationMinus)
        val durationPlus = dialogView.findViewById<Button>(R.id.durationPlus)
        val duration1h = dialogView.findViewById<Button>(R.id.duration1h)
        val duration4h = dialogView.findViewById<Button>(R.id.duration4h)
        val duration8h = dialogView.findViewById<Button>(R.id.duration8h)
        val duration24h = dialogView.findViewById<Button>(R.id.duration24h)

        var currentDurationMinutes = 60 // Default 1 hour

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

            // Calculate end time
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.MINUTE, currentDurationMinutes)
            val endTime = SimpleDateFormat("HH:mm", Locale.US).format(calendar.time)
            val endDate = SimpleDateFormat("MMM dd", Locale.US).format(calendar.time)

            // Show date if more than 24 hours
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
            if (currentDurationMinutes < 10080) { // Max 7 days
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

        // Initialize display
        updateDurationDisplay()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Away Mode")
            .setMessage("Set all zones to energy-saving temperatures:\n• Most zones: 18.5°C\n• Kitchen: 17.5°C")
            .setView(dialogView)
            .setPositiveButton("Enable") { _, _ ->
                enableAwayMode(currentDurationMinutes)
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
    }

    private fun enableAwayMode(durationMinutes: Int) {
        if (allZones.isEmpty()) {
            showError("No zones available")
            return
        }

        showLoading(true)

        lifecycleScope.launch {
            try {
                // Calculate end time
                val calendar = Calendar.getInstance()
                calendar.add(Calendar.MINUTE, durationMinutes)
                val timeUntil = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(calendar.time)

                // Create a list of deferred tasks
                val tasks = allZones.map { zone ->
                    async {
                        try {
                            // Set temperature based on zone name
                            val targetTemp = if (zone.name.equals("Kitchen", ignoreCase = true)) {
                                17.5
                            } else {
                                18.5
                            }

                            val setpoint = HeatSetpoint(
                                HeatSetpointValue = targetTemp,
                                SetpointMode = 2, // Temporary override
                                TimeUntil = timeUntil
                            )

                            val response = EvohomeApiClient.apiService.setTemperature(
                                zoneId = zone.zoneId,
                                auth = "bearer $accessToken",
                                setpoint = setpoint
                            )
                            
                            if (response.isSuccessful) {
                                true // Success
                            } else {
                                Log.e("AwayMode", "Failed to set ${zone.name}: ${response.code()}")
                                false // Failure
                            }
                        } catch (e: Exception) {
                            Log.e("AwayMode", "Error setting ${zone.name}: ${e.message}")
                            false // Failure
                        }
                    }
                }

                // Await all results
                val results = tasks.map { it.await() }
                val successCount = results.count { it }
                val failCount = results.count { !it }

                showLoading(false)

                // Show result
                val durationText = formatDuration(durationMinutes)

                if (failCount == 0) {
                    Snackbar.make(
                        binding.root,
                        "Away mode enabled for $durationText ($successCount zones)",
                        Snackbar.LENGTH_LONG
                    ).show()
                } else {
                    Snackbar.make(
                        binding.root,
                        "Away mode partially set: $successCount succeeded, $failCount failed",
                        Snackbar.LENGTH_LONG
                    ).show()
                }

                // Refresh zone list after delay
                delay(2000)
                loadZones()

                val intent = Intent(this@MainActivity, com.yourname.evohomecontrol.widget.Evohome4x2Widget::class.java).apply {
                    action = com.yourname.evohomecontrol.widget.Evohome4x2Widget.ACTION_DELAYED_REFRESH
                }
                sendBroadcast(intent)

            } catch (e: Exception) {
                showLoading(false)
                showError("Failed to enable away mode: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun cancelAllOverrides() {
        if (allZones.isEmpty()) {
            showError("No zones available")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Cancel All Overrides")
            .setMessage("Return all zones to follow their schedules?")
            .setPositiveButton("Confirm") { _, _ ->
                showLoading(true)

                lifecycleScope.launch {
                    try {
                        // Create a list of deferred tasks
                        val tasks = allZones.map { zone ->
                            async {
                                try {
                                    val setpoint = HeatSetpoint(
                                        HeatSetpointValue = 0.0,
                                        SetpointMode = 0, // Follow schedule
                                        TimeUntil = null
                                    )

                                    val response = EvohomeApiClient.apiService.setTemperature(
                                        zoneId = zone.zoneId,
                                        auth = "bearer $accessToken",
                                        setpoint = setpoint
                                    )

                                    if (response.isSuccessful) {
                                        true // Success
                                    } else {
                                        Log.e("CancelOverrides", "Failed to cancel ${zone.name}: ${response.code()}")
                                        false // Failure
                                    }
                                } catch (e: Exception) {
                                    Log.e("CancelOverrides", "Error canceling ${zone.name}: ${e.message}")
                                    false // Failure
                                }
                            }
                        }

                        // Await all results
                        val results = tasks.map { it.await() }
                        val successCount = results.count { it }
                        val failCount = results.count { !it }

                        showLoading(false)

                        // Show result
                        if (failCount == 0) {
                            Snackbar.make(
                                binding.root,
                                "All zones now following schedules ($successCount zones)",
                                Snackbar.LENGTH_LONG
                            ).show()
                        } else {
                            Snackbar.make(
                                binding.root,
                                "Overrides cancelled: $successCount succeeded, $failCount failed",
                                Snackbar.LENGTH_LONG
                            ).show()
                        }

                        // Refresh zone list
                        delay(2000)
                        loadZones()

                    } catch (e: Exception) {
                        showLoading(false)
                        showError("Failed to cancel overrides: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatDuration(minutes: Int): String {
        val days = minutes / 1440
        val hours = (minutes % 1440) / 60
        val mins = minutes % 60

        return when {
            days > 0 && hours > 0 && mins > 0 -> "${days}d ${hours}h ${mins}m"
            days > 0 && hours > 0 -> "${days}d ${hours}h"
            days > 0 && mins > 0 -> "${days}d ${mins}m"
            days > 0 -> "${days}d"
            hours > 0 && mins > 0 -> "${hours}h ${mins}m"
            hours > 0 -> "${hours}h"
            else -> "${mins}m"
        }
    }

    private fun showSystemModeDialog() {
        val modes = arrayOf("Auto", "Away", "Day Off", "Heating Off", "Custom")

        AlertDialog.Builder(this)
            .setTitle("System Mode")
            .setItems(modes) { _, which ->
                Snackbar.make(binding.root, "System mode: ${modes[which]}", Snackbar.LENGTH_SHORT).show()
                // Implement system mode changes when API endpoint is available
            }
            .show()
    }


    private fun showAboutDialog() {
        val timeSinceUpdate = if (lastSuccessfulUpdate > 0) {
            val minutesOld = (System.currentTimeMillis() - lastSuccessfulUpdate) / 60_000
            "\n\nLast update: ${minutesOld} minutes ago"
        } else {
            "\n\nNo data loaded yet"
        }

        AlertDialog.Builder(this)
            .setTitle("About Evohome Control")
            .setMessage("Version 1.0\n\nUnofficial Honeywell Evohome control app$timeSinceUpdate")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showLoading(show: Boolean) {
        binding.loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
        Log.e("MainActivity", message)
    }

    private fun updateWidget() {
        // Update 2x2 widget
        val intent2x2 = Intent(this, EvohomeWidget::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
        }
        val ids2x2 = AppWidgetManager.getInstance(application).getAppWidgetIds(
            ComponentName(application, EvohomeWidget::class.java)
        )
        intent2x2.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids2x2)
        sendBroadcast(intent2x2)

        // Update 4x2 widget
        val intent4x2 = Intent(this, com.yourname.evohomecontrol.widget.Evohome4x2Widget::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
        }
        val ids4x2 = AppWidgetManager.getInstance(application).getAppWidgetIds(
            ComponentName(application, com.yourname.evohomecontrol.widget.Evohome4x2Widget::class.java)
        )
        intent4x2.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids4x2)
        sendBroadcast(intent4x2)

        Log.d("MainActivity", "Widget update broadcasts sent")
    }

    private fun saveZonesForWidget() {
        val prefs = getSharedPreferences("evohome_prefs", MODE_PRIVATE)
        val zonesJson = com.google.gson.Gson().toJson(allZones)
        prefs.edit().putString("zones_data", zonesJson).apply()
        android.util.Log.d("MainActivity", "Saved ${allZones.size} zones for widget")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val boostChannel = NotificationChannel(
                "evohome_boost",
                "Zone Boost Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications when zones are boosted"
            }

            val connectionChannel = NotificationChannel(
                "evohome_connection",
                "Connection Status",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications about Evohome server connection"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(boostChannel)
            notificationManager.createNotificationChannel(connectionChannel)
        }
    }

    private fun showNotification(zoneName: String, temperature: Double) {
        val notification = NotificationCompat.Builder(this, "evohome_boost")
            .setSmallIcon(R.drawable.ic_boost)
            .setContentTitle("Zone Boosted")
            .setContentText("$zoneName boosted to ${temperature}°C for 1 hour")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(zoneName.hashCode(), notification)
    }

    private fun logout() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout?")
            .setPositiveButton("Yes") { _, _ ->
                stopAutoRefresh()  // ADD THIS LINE

                val prefs = getSharedPreferences("evohome_prefs", MODE_PRIVATE)
                prefs.edit().apply {
                    remove("access_token")
                    remove("refresh_token")
                    remove("user_id")
                    remove("saved_email")
                    remove("saved_password")
                    putBoolean("remember_me", false)
                    remove("last_update_timestamp")
                    apply()
                }

                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
            .setNegativeButton("No", null)
            .show()
    }

    private suspend fun refreshAccessToken(): Boolean {
        return try {
            val prefs = getSharedPreferences("evohome_prefs", MODE_PRIVATE)
            val refreshToken = prefs.getString("refresh_token", "") ?: ""
            val email = prefs.getString("saved_email", "") ?: ""
            val password = prefs.getString("saved_password", "") ?: ""

            if (refreshToken.isEmpty() && (email.isEmpty() || password.isEmpty())) {
                // No way to refresh, need to login again
                return false
            }

            // Try to use refresh token first, fall back to re-login if needed
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

                Log.d("MainActivity", "Token refreshed successfully")
                true
            } else {
                Log.e("MainActivity", "Token refresh failed: ${response.code()}")
                false
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Token refresh error: ${e.message}")
            false
        }
    }

    private fun setupConnectionStatusBar() {
        binding.connectionStatusBar?.setOnClickListener {
            if (!isConnected) {
                loadZones(manualRefresh = true)
            }
        }
    }

    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()

        autoRefreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(AUTO_REFRESH_INTERVAL)

                if (isDataStale()) {
                    Log.d("AutoRefresh", "Data is stale, refreshing...")
                    loadZones(manualRefresh = false)
                }
            }
        }
    }

    private fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
        Log.d("AutoRefresh", "Auto-refresh stopped")
    }

    private fun isDataStale(): Boolean {
        val now = System.currentTimeMillis()
        val timeSinceUpdate = now - lastSuccessfulUpdate
        return timeSinceUpdate > STALE_DATA_THRESHOLD
    }

    private fun isDataFresh(): Boolean {
        return !isDataStale() && isConnected
    }

    private fun checkDataFreshness() {
        // Function kept for compatibility but refresh now happens automatically in onResume
        // No need to show warning since we auto-refresh
    }

    private fun showStaleDataWarning() {
        val minutesOld = (System.currentTimeMillis() - lastSuccessfulUpdate) / 60_000

        AlertDialog.Builder(this)
            .setTitle("Data May Be Outdated")
            .setMessage("Zone data is ${minutesOld} minutes old.\n\nControls may not work as expected until connected.")
            .setPositiveButton("Refresh Now") { _, _ ->
                loadZones(manualRefresh = true)
            }
            .setNegativeButton("Continue Anyway", null)
            .show()
    }

    private fun showConnectionStatus(connected: Boolean, message: String, isWarning: Boolean = false) {
        isConnected = connected

        binding.connectionStatusBar?.visibility = if (!connected || isWarning) View.VISIBLE else View.GONE
        binding.connectionStatusText?.text = message

        val bgColor = when {
            !connected -> Color.parseColor("#D32F2F")
            isWarning -> Color.parseColor("#F57C00")
            else -> Color.parseColor("#388E3C")
        }
        binding.connectionStatusBar?.setBackgroundColor(bgColor)

        binding.zonesRecyclerView?.alpha = if (!connected || isWarning) 0.6f else 1.0f
    }

    private fun onConnectionSuccess() {
        consecutiveFailures = 0
        lastSuccessfulUpdate = System.currentTimeMillis()

        val prefs = getSharedPreferences("evohome_prefs", MODE_PRIVATE)
        prefs.edit().putLong("last_update_timestamp", lastSuccessfulUpdate).apply()

        showConnectionStatus(
            connected = true,
            message = "Connected - Last updated: ${SimpleDateFormat("HH:mm", Locale.US).format(Date())}",
            isWarning = false
        )

        lifecycleScope.launch {
            delay(2000)
            if (isConnected && !isDataStale()) {
                binding.connectionStatusBar?.visibility = View.GONE
            }
        }
    }

    private fun onConnectionFailed(errorMessage: String) {
        consecutiveFailures++

        Log.e("MainActivity", "Connection failed ($consecutiveFailures failures): $errorMessage")

        val minutesOld = if (lastSuccessfulUpdate > 0) {
            (System.currentTimeMillis() - lastSuccessfulUpdate) / 60_000
        } else {
            0
        }

        val message = if (lastSuccessfulUpdate > 0) {
            "Connection lost - Data is ${minutesOld}min old"
        } else {
            "No connection to Evohome server"
        }

        showConnectionStatus(
            connected = false,
            message = message,
            isWarning = false
        )

        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            showConnectionLostNotification()
        }

        Snackbar.make(binding.root, errorMessage, Snackbar.LENGTH_LONG)
            .setAction("Retry") {
                loadZones(manualRefresh = true)
            }
            .show()
    }

    private fun showConnectionLostNotification() {
        val notification = NotificationCompat.Builder(this, "evohome_connection")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Evohome Connection Lost")
            .setContentText("Unable to reach Evohome server. Data may be outdated.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(9999, notification)
    }

    private fun setLunchMode() {
        // Find the kitchen zone
        val kitchenZone = allZones.find { it.name.equals("Kitchen", ignoreCase = true) }

        if (kitchenZone == null) {
            showError("Kitchen zone not found")
            return
        }

        showLoading(true)

        lifecycleScope.launch {
            try {
                // Calculate end time (45 minutes from now)
                val calendar = Calendar.getInstance()
                calendar.add(Calendar.MINUTE, 45)
                val timeUntil = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(calendar.time)

                val setpoint = HeatSetpoint(
                    HeatSetpointValue = 19.5,
                    SetpointMode = 2, // Temporary override
                    TimeUntil = timeUntil
                )

                val response = EvohomeApiClient.apiService.setTemperature(
                    zoneId = kitchenZone.zoneId,
                    auth = "bearer $accessToken",
                    setpoint = setpoint
                )

                showLoading(false)

                if (response.isSuccessful) {
                    Snackbar.make(
                        binding.root,
                        "Lunch mode: Kitchen set to 19.5°C for 45 minutes",
                        Snackbar.LENGTH_LONG
                    ).show()

                    delay(2000)
                    loadZones()
                } else {
                    showError("Failed to set lunch mode: ${response.code()}")
                }
            } catch (e: Exception) {
                showLoading(false)
                showError("Error setting lunch mode: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun showWorkFromHomeDialog(autoClose: Boolean = false) {
        // Find the kitchen zone
        val kitchenZone = allZones.find { it.name.equals("Kitchen", ignoreCase = true) }

        if (kitchenZone == null) {
            showError("Kitchen zone not found")
            if (autoClose) {
                finish()
            }
            return
        }

        // Show time picker dialog
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(Calendar.MINUTE)

        val timePickerDialog = android.app.TimePickerDialog(
            this,
            { _, hour, minute ->
                // Calculate duration until selected time
                val endTime = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)

                    // If time is before now, assume it's tomorrow
                    if (before(Calendar.getInstance())) {
                        add(Calendar.DAY_OF_MONTH, 1)
                    }
                }

                val timeUntil = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(endTime.time)

                // Set kitchen to 18.5°C until selected time
                showLoading(true)

                lifecycleScope.launch {
                    try {
                        val setpoint = HeatSetpoint(
                            HeatSetpointValue = 18.5,
                            SetpointMode = 2, // Temporary override
                            TimeUntil = timeUntil
                        )

                        val response = EvohomeApiClient.apiService.setTemperature(
                            zoneId = kitchenZone.zoneId,
                            auth = "bearer $accessToken",
                            setpoint = setpoint
                        )

                        showLoading(false)

                        if (response.isSuccessful) {
                            val endTimeStr = SimpleDateFormat("HH:mm", Locale.US).format(endTime.time)
                            Snackbar.make(
                                binding.root,
                                "Work from home: Kitchen set to 18.5°C until $endTimeStr",
                                Snackbar.LENGTH_LONG
                            ).show()

                            delay(2000)
                            loadZones()

                            val intent = Intent(this@MainActivity, com.yourname.evohomecontrol.widget.Evohome4x2Widget::class.java).apply {
                                action = com.yourname.evohomecontrol.widget.Evohome4x2Widget.ACTION_DELAYED_REFRESH
                            }
                            sendBroadcast(intent)

                            if (autoClose) {
                                delay(1000) // Small delay to let snackbar show briefly
                                finish()
                            }
                        } else {
                            showError("Failed to set work from home mode: ${response.code()}")
                            if (autoClose) {
                                finish()
                            }
                        }
                    } catch (e: Exception) {
                        showLoading(false)
                        showError("Error setting work from home mode: ${e.message}")
                        e.printStackTrace()
                        if (autoClose) {
                            finish()
                        }
                    }
                }
            },
            currentHour,
            currentMinute,
            true // 24-hour format
        )
        
        timePickerDialog.setOnCancelListener {
             if (autoClose) {
                 finish()
             }
        }
        
        timePickerDialog.show()
    }

    private fun waitForZonesAndExecute(action: () -> Unit) {
        lifecycleScope.launch {
            // If zones are already loaded, execute immediately
            if (allZones.isNotEmpty()) {
                action()
                return@launch
            }

            // If not loaded, show loading and wait
            showLoading(true)
            var attempts = 0
            while (allZones.isEmpty() && attempts < 10) { // Max 10 attempts (10 seconds)
                delay(1000)
                attempts++
            }
            showLoading(false)

            if (allZones.isNotEmpty()) {
                action()
            } else {
                showError("Could not load zones for widget action.")
                finish() // Close activity if zones can't be loaded
            }
        }
    }

    private fun cancelAllOverridesFromWidget(autoClose: Boolean) {
        if (allZones.isEmpty()) {
            showError("No zones available")
            if (autoClose) {
                finish()
            }
            return
        }

        // Skip the confirmation dialog for widget actions
        showLoading(true)

        lifecycleScope.launch {
            try {
                val tasks = allZones.map { zone ->
                    async {
                        try {
                            val setpoint = HeatSetpoint(
                                HeatSetpointValue = 0.0,
                                SetpointMode = 0,
                                TimeUntil = null
                            )

                            val response = EvohomeApiClient.apiService.setTemperature(
                                zoneId = zone.zoneId,
                                auth = "bearer $accessToken",
                                setpoint = setpoint
                            )

                            response.isSuccessful
                        } catch (e: Exception) {
                            Log.e("CancelOverrides", "Error canceling ${zone.name}: ${e.message}")
                            false
                        }
                    }
                }

                val results = tasks.map { it.await() }
                val successCount = results.count { it }

                showLoading(false)

                if (successCount > 0) {
                    // Update widget
                    val intent = Intent(this@MainActivity, com.yourname.evohomecontrol.widget.Evohome4x2Widget::class.java).apply {
                        action = com.yourname.evohomecontrol.widget.Evohome4x2Widget.ACTION_DELAYED_REFRESH
                    }
                    sendBroadcast(intent)

                    if (autoClose) {
                        // Close app and return to home screen
                        finish()
                    } else {
                        loadZones()
                    }
                } else {
                    showError("Failed to cancel overrides")
                    if (autoClose) {
                        finish()
                    }
                }

            } catch (e: Exception) {
                showLoading(false)
                showError("Failed to cancel overrides: ${e.message}")
                if (autoClose) {
                    finish()
                }
            }
        }
    }

    private fun setLunchModeFromWidget(autoClose: Boolean) {
        val kitchenZone = allZones.find { it.name.equals("Kitchen", ignoreCase = true) }

        if (kitchenZone == null) {
            showError("Kitchen zone not found")
            if (autoClose) {
                finish()
            }
            return
        }

        showLoading(true)

        lifecycleScope.launch {
            try {
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

                val response = EvohomeApiClient.apiService.setTemperature(
                    zoneId = kitchenZone.zoneId,
                    auth = "bearer $accessToken",
                    setpoint = setpoint
                )

                showLoading(false)

                if (response.isSuccessful) {
                    // Update widget
                    val intent = Intent(this@MainActivity, com.yourname.evohomecontrol.widget.Evohome4x2Widget::class.java).apply {
                        action = com.yourname.evohomecontrol.widget.Evohome4x2Widget.ACTION_DELAYED_REFRESH
                    }
                    sendBroadcast(intent)

                    if (autoClose) {
                        finish()
                    } else {
                        loadZones()
                    }
                } else {
                    showError("Failed to set lunch mode: ${response.code()}")
                    if (autoClose) {
                        finish()
                    }
                }
            } catch (e: Exception) {
                showLoading(false)
                showError("Error setting lunch mode: ${e.message}")
                if (autoClose) {
                    finish()
                }
            }
        }
    }
}
