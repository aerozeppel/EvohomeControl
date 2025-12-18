package com.yourname.evohomecontrol

data class TemperatureReading(
    val zoneId: String,
    val zoneName: String,
    val temperature: Double,
    val targetTemperature: Double,
    val timestamp: Long = System.currentTimeMillis()
)