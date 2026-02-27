package com.yourname.evohomecontrol.api

import com.google.gson.annotations.SerializedName

// Authentication Response
data class TokenResponse(
    val access_token: String,
    val refresh_token: String,
    val expires_in: Int,
    val token_type: String
)

// User Account
data class Account(
    val userId: String,
    val username: String,
    val firstname: String,
    val lastname: String,
    val streetAddress: String?,
    val city: String?,
    val postcode: String?,
    val country: String?,
    val language: String?
)

// Installation (Location + Gateways)
data class Installation(
    val locationInfo: LocationInfo,
    val gateways: List<Gateway>
)

data class LocationInfo(
    val locationId: String,
    val name: String
)

// Gateway
data class Gateway(
    val gatewayInfo: Any?,  // Raw JSON, nullable
    val temperatureControlSystems: List<TemperatureControlSystem>
)

// Temperature Control System
data class TemperatureControlSystem(
    val systemId: String,
    val modelType: String,
    val zones: List<Zone>
)

data class ActiveFault(
    val faultType: String,
    val since: String
)

// Zone - UPDATED with heat demand
data class Zone(
    val zoneId: String,
    val name: String,
    val modelType: String?,
    val zoneType: String?,
    val temperatureStatus: TemperatureStatus,
    val heatSetpointStatus: HeatSetpointStatus,
    val heatDemand: HeatDemand? = null,  // Added heat demand (nullable for compatibility)
    val activeFaults: List<ActiveFault> = emptyList()
)

// Temperature Status
data class TemperatureStatus(
    val temperature: Double,
    val isAvailable: Boolean
)

// Heat Setpoint Status
data class HeatSetpointStatus(
    val targetTemperature: Double,
    val setpointMode: String
)

// Heat Demand - NEW
data class HeatDemand(
    val demandPercentage: Int,  // 0-100 percentage
    val isActive: Boolean = false  // Derived: true if demandPercentage > 0
)

// Zone Schedule (for both read and write)
data class ZoneSchedule(
    val dailySchedules: List<DailySchedule>
)

data class DailySchedule(
    val dayOfWeek: String,
    val switchpoints: List<Switchpoint>
)

// Switchpoint with dual serialization support
// The API uses different field names for GET vs PUT operations:
// - GET returns: "temperature" and "timeOfDay" (lowercase)
// - PUT expects: "TargetTemperature" and "TimeOfDay" (PascalCase)
data class Switchpoint(
    @SerializedName(value = "TargetTemperature", alternate = ["temperature"])
    val temperature: Double,
    
    @SerializedName(value = "TimeOfDay", alternate = ["timeOfDay"])
    val timeOfDay: String
)

// API Request Models
data class HeatSetpoint(
    val HeatSetpointValue: Double,
    val SetpointMode: Int,  // 0=FollowSchedule, 1=PermanentOverride, 2=TemporaryOverride
    val TimeUntil: String? = null  // Format: "YYYY-MM-DDTHH:MM:SSZ"
)