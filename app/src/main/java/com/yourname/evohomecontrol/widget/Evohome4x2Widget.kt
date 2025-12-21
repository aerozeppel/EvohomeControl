package com.yourname.evohomecontrol.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.yourname.evohomecontrol.MainActivity
import com.yourname.evohomecontrol.R
import com.yourname.evohomecontrol.WidgetZoneEditActivity
import com.yourname.evohomecontrol.api.Zone

class Evohome4x2Widget : AppWidgetProvider() {
    
    companion object {
        const val ACTION_AWAY = "com.yourname.evohomecontrol.ACTION_AWAY"
        const val ACTION_RETURN_HOME = "com.yourname.evohomecontrol.ACTION_RETURN_HOME"
        const val ACTION_LUNCH = "com.yourname.evohomecontrol.ACTION_LUNCH"
        const val ACTION_WORK_FROM_HOME = "com.yourname.evohomecontrol.ACTION_WORK_FROM_HOME"
    }
    
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_AWAY, ACTION_RETURN_HOME, ACTION_LUNCH, ACTION_WORK_FROM_HOME -> {
                // Start MainActivity with the action, but finish it after executing
                val mainIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION
                    when (intent.action) {
                        ACTION_AWAY -> putExtra("SHOW_AWAY_DIALOG", true)
                        ACTION_RETURN_HOME -> {
                            putExtra("CANCEL_ALL_OVERRIDES", true)
                            putExtra("AUTO_CLOSE", true) // New flag to auto-close
                        }
                        ACTION_LUNCH -> {
                            putExtra("SET_LUNCH_MODE", true)
                            putExtra("AUTO_CLOSE", true) // New flag to auto-close
                        }
                        ACTION_WORK_FROM_HOME -> putExtra("SHOW_WORK_FROM_HOME_DIALOG", true)
                    }
                }
                context.startActivity(mainIntent)
            }
        }
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
            
            // Setup refresh button
            val refreshIntent = Intent(context, Evohome4x2Widget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
            }
            val refreshPendingIntent = PendingIntent.getBroadcast(
                context, 
                0, 
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

    private fun updateZoneView(context: Context, views: RemoteViews, zone: Zone?, zoneNumber: Int) {
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
            views.setTextViewText(nameId, zone.name)
            
            val currentTemp = String.format("%.1f", zone.temperatureStatus.temperature)
            views.setTextViewText(tempId, currentTemp)
            
            val targetTemp = String.format("%.1f", zone.heatSetpointStatus.targetTemperature)
            views.setTextViewText(targetId, "→$targetTemp°")
            
            // Setup zone container click - opens the zone edit dialog
            val editIntent = Intent(context, WidgetZoneEditActivity::class.java).apply {
                putExtra("ZONE_ID", zone.zoneId)
                putExtra("ZONE_NAME", zone.name)
                putExtra("CURRENT_TEMP", zone.temperatureStatus.temperature)
                putExtra("TARGET_TEMP", zone.heatSetpointStatus.targetTemperature)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            
            Log.d("Evohome4x2Widget", "Setting up edit button for ${zone.name}")
            
            val editPendingIntent = PendingIntent.getActivity(
                context,
                zoneNumber + 100, // Different request code from mode buttons
                editIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            views.setOnClickPendingIntent(containerId, editPendingIntent)
        } else {
            views.setTextViewText(nameId, "---")
            views.setTextViewText(tempId, "--")
            views.setTextViewText(targetId, "--")
        }
    }
}
