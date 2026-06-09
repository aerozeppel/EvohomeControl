package com.yourname.evohomecontrol

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.yourname.evohomecontrol.api.ScheduleBackup

class ScheduleBackupManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val PREF_NAME = "evohome_schedule_backups"
        private const val KEY_BACKUPS = "backups"
    }

    // Load all backups
    fun getBackups(): List<ScheduleBackup> {
        val json = prefs.getString(KEY_BACKUPS, null) ?: return emptyList()
        val type = object : TypeToken<List<ScheduleBackup>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Save a new backup or update an existing one
    fun saveBackup(backup: ScheduleBackup) {
        val currentBackups = getBackups().toMutableList()
        // If updating an existing backup with same ID, replace it
        val existingIndex = currentBackups.indexOfFirst { it.id == backup.id }
        if (existingIndex != -1) {
            currentBackups[existingIndex] = backup
        } else {
            currentBackups.add(backup)
        }
        
        // Sort by newest first
        currentBackups.sortByDescending { it.timestamp }
        
        saveAll(currentBackups)
    }

    // Delete a backup
    fun deleteBackup(backupId: String) {
        val currentBackups = getBackups().toMutableList()
        currentBackups.removeAll { it.id == backupId }
        saveAll(currentBackups)
    }

    private fun saveAll(backups: List<ScheduleBackup>) {
        val json = gson.toJson(backups)
        prefs.edit().putString(KEY_BACKUPS, json).apply()
    }
    
    // Export single backup directly to string
    fun exportBackup(backup: ScheduleBackup): String {
        return gson.toJson(backup)
    }
    
    // Import backup string and save
    fun importBackup(jsonString: String): ScheduleBackup? {
        return try {
            val backup = gson.fromJson(jsonString, ScheduleBackup::class.java)
            if (backup != null && backup.id.isNotEmpty() && backup.name.isNotEmpty() && backup.schedules.isNotEmpty()) {
                // To avoid duplicate IDs if importing the same one again, we could generate a new ID, 
                // but keeping the same ID means it acts as an "update" if they already had it.
                // Let's generate a new ID + "-imported" to be safe, or just keep it.
                // We'll just keep it and `saveBackup` will replace if it already exists.
                saveBackup(backup)
                backup
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
