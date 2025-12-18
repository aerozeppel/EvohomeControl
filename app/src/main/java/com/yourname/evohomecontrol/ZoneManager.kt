package com.yourname.evohomecontrol

import com.yourname.evohomecontrol.api.Zone

class ZoneManager(private val zones: List<Zone>) {
    
    // Get a specific zone by name (from Go: Zone(string))
    fun getZone(name: String): Zone? {
        return zones.find { it.name.equals(name, ignoreCase = true) }
    }
    
    // Get all zone names (from Go: ZoneNames())
    fun getZoneNames(): List<String> {
        return zones.map { it.name }
    }
    
    // Get zones with overrides (from Go: ZoneNamesWithOverride())
    fun getZonesWithOverride(): List<Zone> {
        return zones.filter { 
            it.heatSetpointStatus.setpointMode != "FollowSchedule" 
        }
    }
    
    fun getZoneNamesWithOverride(): List<String> {
        return getZonesWithOverride().map { it.name }
    }
    
    // Get zones as map (from Go: ZonesMap())
    fun getZonesMap(): Map<String, Zone> {
        return zones.associateBy { it.name }
    }
    
    // Check if zone is currently heating
    fun isZoneHeating(zone: Zone): Boolean {
        return zone.temperatureStatus.isAvailable &&
               zone.temperatureStatus.temperature < zone.heatSetpointStatus.targetTemperature
    }
    
    fun getHeatingZones(): List<Zone> {
        return zones.filter { isZoneHeating(it) }
    }
    
    fun getZoneCount(): Int = zones.size
    
    fun getAverageTargetTemp(): Double {
        if (zones.isEmpty()) return 0.0
        return zones.map { it.heatSetpointStatus.targetTemperature }.average()
    }
    
    fun getAverageCurrentTemp(): Double {
        val availableZones = zones.filter { it.temperatureStatus.isAvailable }
        if (availableZones.isEmpty()) return 0.0
        return availableZones.map { it.temperatureStatus.temperature }.average()
    }
}