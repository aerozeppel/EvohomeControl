package com.yourname.evohomecontrol

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.yourname.evohomecontrol.api.Zone
import com.yourname.evohomecontrol.databinding.ZoneItemBinding

class ZoneAdapter(
    private var zones: List<Zone>,
    private val onTempClick: (Zone) -> Unit,
    private val onScheduleClick: (Zone) -> Unit
) : RecyclerView.Adapter<ZoneAdapter.ZoneViewHolder>() {

    private var zoneManager: ZoneManager = ZoneManager(zones)

    class ZoneViewHolder(val binding: ZoneItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ZoneViewHolder {
        val binding = ZoneItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ZoneViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ZoneViewHolder, position: Int) {
        val zone = zones[position]
        val binding = holder.binding

        // Zone name - uppercase
        binding.zoneName.text = zone.name.uppercase()

        // Current temperature - just the number and degree symbol
        binding.currentTemp.text = String.format("%.1f°", zone.temperatureStatus.temperature)

        // Target temperature - just the number and degree symbol
        binding.targetTemp.text = String.format("%.1f°", zone.heatSetpointStatus.targetTemperature)

        // Status indicator
        val hasOverride = zone.heatSetpointStatus.setpointMode != "FollowSchedule"
        binding.statusIndicator.text = if (hasOverride) {
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

        binding.targetSection.setBackgroundColor(targetColor)

        // Heat demand indicator
        val tempDiff = targetTemp - currentTemp
        if (tempDiff > 0.1) {
            binding.heatDemandContainer.visibility = View.VISIBLE

            val estimatedDemand = when {
                tempDiff >= 2.0 -> 100
                tempDiff >= 1.5 -> 90
                tempDiff >= 1.0 -> 70
                tempDiff >= 0.5 -> 40
                else -> 20
            }.coerceIn(0, 100)

            binding.heatDemand.text = "${estimatedDemand}%"
        } else {
            binding.heatDemandContainer.visibility = View.GONE
        }

        // Battery warning
        val hasBatteryFault = zone.activeFaults.any { 
            it.faultType.contains("Battery", ignoreCase = true) 
        }
        binding.batteryWarningContainer.visibility = if (hasBatteryFault) View.VISIBLE else View.GONE

        // Button clicks
        binding.tempButton.setOnClickListener {
            onTempClick(zone)
        }
        
        binding.scheduleButton.setOnClickListener {
            onScheduleClick(zone)
        }
    }

    override fun getItemCount() = zones.size

    fun updateZones(newZones: List<Zone>) {
        val diffCallback = ZoneDiffCallback(zones, newZones)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        
        zones = newZones
        zoneManager = ZoneManager(newZones)
        
        diffResult.dispatchUpdatesTo(this)
    }
    
    fun getZoneManager(): ZoneManager = zoneManager

    class ZoneDiffCallback(
        private val oldList: List<Zone>,
        private val newList: List<Zone>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].zoneId == newList[newItemPosition].zoneId
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            // Simplified content check - assumes data classes with proper equals()
            // or we can compare specific fields if needed
            return oldList[oldItemPosition] == newList[newItemPosition]
        }
    }
}
