package com.yourname.evohomecontrol

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.yourname.evohomecontrol.api.Zone

class ZoneAdapter(
    private var zones: List<Zone>,
    private val onTempClick: (Zone) -> Unit,
    private val onScheduleClick: (Zone) -> Unit
) : RecyclerView.Adapter<ZoneAdapter.ZoneViewHolder>() {

    private var zoneManager: ZoneManager = ZoneManager(zones)

    class ZoneViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val zoneName: TextView = view.findViewById(R.id.zoneName)
        val currentTemp: TextView = view.findViewById(R.id.currentTemp)
        val targetTemp: TextView = view.findViewById(R.id.targetTemp)
        val statusIndicator: TextView = view.findViewById(R.id.statusIndicator)
        val targetSection: LinearLayout = view.findViewById(R.id.targetSection)
        val heatDemandContainer: LinearLayout = view.findViewById(R.id.heatDemandContainer)
        val heatDemand: TextView = view.findViewById(R.id.heatDemand)
        val tempButton: ImageButton = view.findViewById(R.id.tempButton)
        val scheduleButton: ImageButton = view.findViewById(R.id.scheduleButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ZoneViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.zone_item, parent, false)
        return ZoneViewHolder(view)
    }

    override fun onBindViewHolder(holder: ZoneViewHolder, position: Int) {
        val zone = zones[position]
    
        // Zone name - uppercase
        holder.zoneName.text = zone.name.uppercase()
    
        // Current temperature - just the number and degree symbol
        holder.currentTemp.text = String.format("%.1f°", zone.temperatureStatus.temperature)
    
        // Target temperature - just the number and degree symbol
        holder.targetTemp.text = String.format("%.1f°", zone.heatSetpointStatus.targetTemperature)
    
        // Status indicator
        val hasOverride = zone.heatSetpointStatus.setpointMode != "FollowSchedule"
        holder.statusIndicator.text = if (hasOverride) {
            "OVERRIDE ACTIVE"
        } else {
            "FOLLOWING SCHEDULE"
        }
    
        // Color the bottom section based on temperature
        val targetTemp = zone.heatSetpointStatus.targetTemperature
        val currentTemp = zone.temperatureStatus.temperature
    
        val targetColor = when {
            targetTemp >= 20.0 -> Color.parseColor("#FF6B35") // Warm Orange
            targetTemp >= 18.0 -> Color.parseColor("#FFA726") // Medium Orange
            else -> Color.parseColor("#00897B") // Cool Teal
        }
    
        holder.targetSection.setBackgroundColor(targetColor)
    
        // Heat demand indicator
        val tempDiff = targetTemp - currentTemp
        if (tempDiff > 0.1) {
            holder.heatDemandContainer.visibility = View.VISIBLE
        
            val estimatedDemand = when {
                tempDiff >= 2.0 -> 100
                tempDiff >= 1.5 -> 90
                tempDiff >= 1.0 -> 70
                tempDiff >= 0.5 -> 40
                else -> 20
            }.coerceIn(0, 100)
        
            holder.heatDemand.text = "${estimatedDemand}%"
        } else {
            holder.heatDemandContainer.visibility = View.GONE
        }
    
        // Button clicks
        holder.tempButton.setOnClickListener {
            onTempClick(zone)
        }
        
        holder.scheduleButton.setOnClickListener {
            onScheduleClick(zone)
        }
    }

    override fun getItemCount() = zones.size

    fun updateZones(newZones: List<Zone>) {
        zones = newZones
        zoneManager = ZoneManager(newZones)
        notifyDataSetChanged()
    }
    
    fun getZoneManager(): ZoneManager = zoneManager
}
