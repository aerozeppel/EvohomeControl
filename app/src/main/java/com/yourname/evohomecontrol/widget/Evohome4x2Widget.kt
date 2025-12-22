package com.yourname.evohomecontrol.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.yourname.evohomecontrol.MainActivity
import com.yourname.evohomecontrol.R
import com.yourname.evohomecontrol.WidgetZoneEditActivity
import com.yourname.evohomecontrol.api.Zone
import android.os.Bundle
import java.util.concurrent.TimeUnit

class Evohome4x2Widget : AppWidgetProvider() {
    
    companion object {
        const val ACTION_AWAY = "com.yourname.evohomecontrol.ACTION_AWAY"
        const val ACTION_RETURN_HOME = "com.yourname.evohomecontrol.ACTION_RETURN_HOME"
        const val ACTION_LUNCH = "com.yourname.evohomecontrol.ACTION_LUNCH"
        const val ACTION_WORK_FROM_HOME = "com.yourname.evohomecontrol.ACTION_WORK_FROM_HOME"
        const val ACTION_AUTO_REFRESH = "com.yourname.evohomecontrol.ACTION_AUTO_REFRESH"
        const val ACTION_FETCH_DATA = "com.yourname.evohomecontrol.ACTION_FETCH_DATA"
        const val ACTION_DELAYED_REFRESH = "com.yourname.evohomecontrol.ACTION_DELAYED_REFRESH"
        const val ACTION_MANUAL_REFRESH = "com.yourname.evohomecontrol.ACTION_MANUAL_REFRESH" 
        private const val REFRESH_INTERVAL_MILLIS = 10 * 1000L // 10 seconds
        private const val PREFS_NAME = "widget_prefs"
        private const val KEY_WIDGET_VISIBLE = "widget_visible_"
    }
    
override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
    Log.d("Evohome4x2Widget", "onUpdate called for ${appWidgetIds.size} widgets")
    
    // Update all widgets
    for (appWidgetId in appWidgetIds) {
        updateAppWidget(context, appWidgetManager, appWidgetId)
    }
}

override fun onDisabled(context: Context) {
    super.onDisabled(context)
    Log.d("Evohome4x2Widget", "All widgets removed")
    
    // Clear visibility preferences
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().clear().apply()
}

override fun onAppWidgetOptionsChanged(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetId: Int,
    newOptions: Bundle
) {
    super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
    
    Log.d("Evohome4x2Widget", "Widget $appWidgetId became visible - triggering refresh")
    
    // Trigger fresh data when user returns to home screen
    triggerDataFetch(context)
    
    // Update widget display after short delay
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        updateAppWidget(context, appWidgetManager, appWidgetId)
    }, 3000)
}

override fun onEnabled(context: Context) {
    super.onEnabled(context)

}






    
    override fun onReceive(context: Context, intent: Intent) {
    super.onReceive(context, intent)

    Log.d("Evohome4x2Widget", "onReceive: ${intent.action}")

    when (intent.action) {

        ACTION_MANUAL_REFRESH -> {
            Log.d("Evohome4x2Widget", "Manual refresh button pressed")
            
            // Trigger fresh data fetch via WorkManager
            triggerDataFetch(context)
            
            // Schedule widget UI update after data fetch completes (3 seconds)
            scheduleDelayedRefresh(context)
        }
        ACTION_DELAYED_REFRESH -> {
            Log.d("Evohome4x2Widget", "Delayed refresh requested, scheduling 3-second delay...")
            scheduleDelayedRefresh(context)
        }
        AppWidgetManager.ACTION_APPWIDGET_UPDATE -> {
            Log.d("Evohome4x2Widget", "Manual refresh triggered")
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisAppWidget = ComponentName(context.packageName, Evohome4x2Widget::class.java.name)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisAppWidget)
            
            // Handle both with and without EXTRA_APPWIDGET_IDS
            val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS) ?: appWidgetIds
            onUpdate(context, appWidgetManager, ids)
        }
        Intent.ACTION_SCREEN_ON -> {
            Log.d("Evohome4x2Widget", "Screen turned on - refreshing widget")
            triggerDataFetch(context)
            
            // Update all widgets after delay
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val thisAppWidget = ComponentName(context.packageName, Evohome4x2Widget::class.java.name)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(thisAppWidget)
                appWidgetIds.forEach { id ->
                    updateAppWidget(context, appWidgetManager, id)
                }
            }, 3000)
        }
        ACTION_AWAY, ACTION_RETURN_HOME, ACTION_LUNCH, ACTION_WORK_FROM_HOME -> {
    Log.d("Evohome4x2Widget", "Quick action triggered: ${intent.action}")
    
    // Schedule delayed data fetch (20 seconds) to give API time to process
    triggerDelayedDataFetch(context, 20)
    
    // Start MainActivity with the action
    val mainIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION
        when (intent.action) {
            ACTION_AWAY -> putExtra("SHOW_AWAY_DIALOG", true)
            ACTION_RETURN_HOME -> {
                putExtra("CANCEL_ALL_OVERRIDES", true)
                putExtra("AUTO_CLOSE", true)
            }
            ACTION_LUNCH -> {
                putExtra("SET_LUNCH_MODE", true)
                putExtra("AUTO_CLOSE", true)
            }
            ACTION_WORK_FROM_HOME -> {
                putExtra("SHOW_WORK_FROM_HOME_DIALOG", true)
                putExtra("AUTO_CLOSE", true)
            }
        }
    }
    context.startActivity(mainIntent)
}
    }
}

private fun triggerDataFetch(context: Context) {
    Log.d("Evohome4x2Widget", "Triggering data fetch via WorkManager")
    
    val constraints = androidx.work.Constraints.Builder()
        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
        .build()
    
    val workRequest = androidx.work.OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
        .setConstraints(constraints)
        .build()
    
    androidx.work.WorkManager.getInstance(context).enqueue(workRequest)
}

private fun triggerDelayedDataFetch(context: Context, delaySeconds: Long) {
    Log.d("Evohome4x2Widget", "Scheduling delayed data fetch in $delaySeconds seconds")
    
    val constraints = androidx.work.Constraints.Builder()
        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
        .build()
    
    val workRequest = androidx.work.OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
        .setInitialDelay(delaySeconds, java.util.concurrent.TimeUnit.SECONDS)
        .setConstraints(constraints)
        .build()
    
    androidx.work.WorkManager.getInstance(context).enqueue(workRequest)
}




    



    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_evohome_4x2)

        // Load zone data
        val prefs = context.getSharedPreferences("evohome_prefs", Context.MODE_PRIVATE)
        val zonesJson = prefs.getString("zones_data", "[]") ?: "[]"
        
        Log.d("Evohome4x2Widget", "Zones JSON: $zonesJson")
        
        try {
            val type = object : TypeToken<List<Zone>>() {}.type
            val zones = Gson().fromJson<List<Zone>>(zonesJson, type) ?: emptyList()
            
            Log.d("Evohome4x2Widget", "Loaded ${zones.size} zones")

            // Add detailed zone logging
            zones.forEachIndexed { index, zone ->
                Log.d("Evohome4x2Widget", "Zone $index: name='${zone.name}', temp=${zone.temperatureStatus.temperature}, target=${zone.heatSetpointStatus.targetTemperature}, available=${zone.temperatureStatus.isAvailable}")
            }

            // Update zones
            if (zones.isNotEmpty()) {
                updateZoneView(context, views, zones.getOrNull(0), 1)
                updateZoneView(context, views, zones.getOrNull(1), 2)
                updateZoneView(context, views, zones.getOrNull(2), 3)
                updateZoneView(context, views, zones.getOrNull(3), 4)
            } else {
                updateZoneView(context, views, null, 1)
                updateZoneView(context, views, null, 2)
                updateZoneView(context, views, null, 3)
                updateZoneView(context, views, null, 4)
            }
            
            // Setup mode buttons
            setupModeButton(context, views, R.id.awayButton, ACTION_AWAY)
            setupModeButton(context, views, R.id.returnHomeButton, ACTION_RETURN_HOME)
            setupModeButton(context, views, R.id.lunchButton, ACTION_LUNCH)
            setupModeButton(context, views, R.id.workFromHomeButton, ACTION_WORK_FROM_HOME)
            
            // Setup refresh button - triggers fresh data fetch
            val refreshIntent = Intent(context, Evohome4x2Widget::class.java).apply {
                action = "com.yourname.evohomecontrol.ACTION_MANUAL_REFRESH"
            }
            val refreshPendingIntent = PendingIntent.getBroadcast(
                context, 
                999, // Unique request code
                refreshIntent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.refreshButton, refreshPendingIntent)
            
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("Evohome4x2Widget", "Error updating widget: ${e.message}")
        }

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
    
    private fun setupModeButton(context: Context, views: RemoteViews, buttonId: Int, action: String) {
        val intent = Intent(context, Evohome4x2Widget::class.java).apply {
            this.action = action
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(buttonId, pendingIntent)
    }

    private fun scheduleDelayedRefresh(context: Context) {
        Log.d("Evohome4x2Widget", "Scheduling delayed refresh in 3 seconds...")
        
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, Evohome4x2Widget::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
        }
        
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val thisAppWidget = ComponentName(context.packageName, Evohome4x2Widget::class.java.name)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(thisAppWidget)
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            1001, // Unique request code for delayed refresh
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Schedule refresh for 3 seconds from now
        val triggerAtMillis = SystemClock.elapsedRealtime() + 3000L
        alarmManager.setExact(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAtMillis,
            pendingIntent
        )
        
        Log.d("Evohome4x2Widget", "Scheduled widget refresh in 3 seconds")
    }

    private fun updateZoneView(context: Context, views: RemoteViews, zone: Zone?, zoneNumber: Int) {
    Log.d("Evohome4x2Widget", "updateZoneView - Zone $zoneNumber: ${zone?.name ?: "NULL"}")
    
    val containerId = when(zoneNumber) {
        1 -> R.id.zone1Container
        2 -> R.id.zone2Container
        3 -> R.id.zone3Container
        4 -> R.id.zone4Container
        else -> return
    }
    
    val nameId = when(zoneNumber) {
        1 -> R.id.zone1Name
        2 -> R.id.zone2Name
        3 -> R.id.zone3Name
        4 -> R.id.zone4Name
        else -> return
    }
    
    val statusId = when(zoneNumber) {
        1 -> R.id.zone1Status
        2 -> R.id.zone2Status
        3 -> R.id.zone3Status
        4 -> R.id.zone4Status
        else -> return
    }
    
    val flameId = when(zoneNumber) {
        1 -> R.id.zone1Flame
        2 -> R.id.zone2Flame
        3 -> R.id.zone3Flame
        4 -> R.id.zone4Flame
        else -> return
    }
    
    val tempId = when(zoneNumber) {
        1 -> R.id.zone1Temp
        2 -> R.id.zone2Temp
        3 -> R.id.zone3Temp
        4 -> R.id.zone4Temp
        else -> return
    }
    
    val targetId = when(zoneNumber) {
        1 -> R.id.zone1Target
        2 -> R.id.zone2Target
        3 -> R.id.zone3Target
        4 -> R.id.zone4Target
        else -> return
    }

    if (zone != null) {
        Log.d("Evohome4x2Widget", "Setting zone $zoneNumber data: name=${zone.name}, temp=${zone.temperatureStatus.temperature}, target=${zone.heatSetpointStatus.targetTemperature}")
        
        // Set zone name
        views.setTextViewText(nameId, zone.name)
        
        // Set status text
        val hasOverride = zone.heatSetpointStatus.setpointMode != "FollowSchedule"
        val statusText = if (hasOverride) "Override" else "Following"
        views.setTextViewText(statusId, statusText)
        Log.d("Evohome4x2Widget", "Zone $zoneNumber status: $statusText")
        
        // Set flame icon visibility based on heat demand
        val currentTemp = zone.temperatureStatus.temperature
        val targetTemp = zone.heatSetpointStatus.targetTemperature
        val isHeating = zone.temperatureStatus.isAvailable && (currentTemp < targetTemp - 0.1)
        
        views.setViewVisibility(flameId, if (isHeating) View.VISIBLE else View.GONE)
        Log.d("Evohome4x2Widget", "Zone $zoneNumber heating: $isHeating (current: $currentTemp, target: $targetTemp)")
        
        // Set current temperature
        val currentTempStr = String.format("%.1f", currentTemp)
        views.setTextViewText(tempId, currentTempStr)
        Log.d("Evohome4x2Widget", "Zone $zoneNumber temp display: $currentTempStr")
        
        // Set target temperature
        val targetTempStr = String.format("%.1f", targetTemp)
        views.setTextViewText(targetId, "→${targetTempStr}°")
        Log.d("Evohome4x2Widget", "Zone $zoneNumber target display: →${targetTempStr}°")
        
        // Setup zone container click - opens the zone edit dialog
        val editIntent = Intent(context, WidgetZoneEditActivity::class.java).apply {
            putExtra("ZONE_ID", zone.zoneId)
            putExtra("ZONE_NAME", zone.name)
            putExtra("CURRENT_TEMP", currentTemp)
            putExtra("TARGET_TEMP", targetTemp)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        Log.d("Evohome4x2Widget", "Setting up edit button for ${zone.name}")
        
        val editPendingIntent = PendingIntent.getActivity(
            context,
            zoneNumber + 100,
            editIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        views.setOnClickPendingIntent(containerId, editPendingIntent)
    } else {
        Log.d("Evohome4x2Widget", "Setting zone $zoneNumber to empty state")
        views.setTextViewText(nameId, "---")
        views.setTextViewText(statusId, "")
        views.setViewVisibility(flameId, View.GONE)
        views.setTextViewText(tempId, "---")
        views.setTextViewText(targetId, "---")
    }
}
}
