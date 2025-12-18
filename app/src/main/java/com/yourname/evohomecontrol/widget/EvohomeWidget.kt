package com.yourname.evohomecontrol.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.yourname.evohomecontrol.R
import com.yourname.evohomecontrol.api.Zone
import com.yourname.evohomecontrol.WidgetBoostActivity

class EvohomeWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_evohome)

        // Load zone data from shared preferences
        val prefs = context.getSharedPreferences("evohome_prefs", Context.MODE_PRIVATE)
        val zonesJson = prefs.getString("zones_data", "[]") ?: "[]"
        
        android.util.Log.d("EvohomeWidget", "Zones JSON: $zonesJson")
        
        try {
            val type = object : TypeToken<List<Zone>>() {}.type
            val zones = Gson().fromJson<List<Zone>>(zonesJson, type) ?: emptyList()
            
            android.util.Log.d("EvohomeWidget", "Loaded ${zones.size} zones")

            // Update up to 4 zones in the widget (in quadrants)
            if (zones.isNotEmpty()) {
                updateZoneView(context, views, zones.getOrNull(0), 1)
                updateZoneView(context, views, zones.getOrNull(1), 2)
                updateZoneView(context, views, zones.getOrNull(2), 3)
                updateZoneView(context, views, zones.getOrNull(3), 4)
            } else {
                // Show empty states for all zones
                updateZoneView(context, views, null, 1)
                updateZoneView(context, views, null, 2)
                updateZoneView(context, views, null, 3)
                updateZoneView(context, views, null, 4)
            }
            
            // Setup refresh button
            val refreshIntent = Intent(context, EvohomeWidget::class.java).apply {
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
            android.util.Log.e("EvohomeWidget", "Error updating widget: ${e.message}")
        }

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private fun updateZoneView(context: Context, views: RemoteViews, zone: Zone?, zoneNumber: Int) {
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
        
        val boostButtonId = when(zoneNumber) {
            1 -> R.id.zone1Boost
            2 -> R.id.zone2Boost
            3 -> R.id.zone3Boost
            4 -> R.id.zone4Boost
            else -> return
        }

        if (zone != null) {
            views.setTextViewText(nameId, zone.name)
            
            // Format temperature to 1 decimal place, no degree symbol (it's in the layout)
            val currentTemp = String.format("%.1f", zone.temperatureStatus.temperature)
            views.setTextViewText(tempId, currentTemp)
            
            // Format target temperature
            val targetTemp = String.format("%.1f", zone.heatSetpointStatus.targetTemperature)
            views.setTextViewText(targetId, targetTemp)
            
            // Setup boost button click - opens the dialog activity
            val boostIntent = Intent(context, WidgetBoostActivity::class.java).apply {
                putExtra("ZONE_ID", zone.zoneId)
                putExtra("ZONE_NAME", zone.name)
                putExtra("TARGET_TEMP", zone.heatSetpointStatus.targetTemperature)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            
            android.util.Log.d("EvohomeWidget", "Setting up boost button for ${zone.name}")
            
            val boostPendingIntent = PendingIntent.getActivity(
                context,
                zoneNumber,
                boostIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            views.setOnClickPendingIntent(boostButtonId, boostPendingIntent)
        } else {
            // Empty state
            views.setTextViewText(nameId, "---")
            views.setTextViewText(tempId, "--")
            views.setTextViewText(targetId, "--")
        }
    }
}