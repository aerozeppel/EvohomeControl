package com.yourname.evohomecontrol

import android.app.TimePickerDialog
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.google.android.material.chip.ChipGroup
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.yourname.evohomecontrol.api.*
import com.yourname.evohomecontrol.databinding.ActivityScheduleEditorBinding
import com.yourname.evohomecontrol.databinding.ItemSwitchpointBinding
import kotlinx.coroutines.*
import retrofit2.HttpException
import java.text.DecimalFormat
import java.util.Calendar

class ScheduleEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScheduleEditorBinding
    internal val schedule = mutableMapOf<String, MutableList<Switchpoint>>()
    private val daysOfWeek = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    internal var currentDay = "Monday"
    private lateinit var adapter: SwitchpointAdapter
    private var hasUnsavedChanges = false
    
    // API related
    private val apiService = EvohomeApiClient.apiService
    private var zoneId: String? = null
    private var zoneName: String? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Activity result launcher for switchpoint editor
    internal val switchpointEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val position = data?.getIntExtra(SwitchpointEditorActivity.EXTRA_POSITION, -1) ?: -1
            val delete = data?.getBooleanExtra("delete", false) ?: false
            val isNew = data?.getBooleanExtra(SwitchpointEditorActivity.EXTRA_IS_NEW, false) ?: false
            
            val switchpoints = schedule[currentDay] ?: return@registerForActivityResult
            
            if (delete && position >= 0) {
                // Delete existing switchpoint
                val sortedList = switchpoints.sortedBy { it.timeOfDay }
                switchpoints.remove(sortedList[position])
                markAsModified()
                updateUI()
                Toast.makeText(this, "Switchpoint deleted", Toast.LENGTH_SHORT).show()
            } else if (isNew) {
                // Add new switchpoint
                val newTemp = data?.getDoubleExtra(SwitchpointEditorActivity.EXTRA_TEMPERATURE, 20.0) ?: 20.0
                val newTime = data?.getStringExtra(SwitchpointEditorActivity.EXTRA_TIME) ?: "00:00"
                
                // Check if time already exists
                if (switchpoints.any { it.timeOfDay == newTime }) {
                    Toast.makeText(this, "A switchpoint already exists at this time", Toast.LENGTH_SHORT).show()
                } else {
                    switchpoints.add(Switchpoint(newTemp, newTime))
                    markAsModified()
                    updateUI()
                    Toast.makeText(this, "Switchpoint added", Toast.LENGTH_SHORT).show()
                }
            } else if (position >= 0) {
                // Update existing switchpoint temperature
                val newTemp = data?.getDoubleExtra(SwitchpointEditorActivity.EXTRA_TEMPERATURE, 0.0) ?: 0.0
                val sortedList = switchpoints.sortedBy { it.timeOfDay }
                val oldSwitchpoint = sortedList[position]
                switchpoints.remove(oldSwitchpoint)
                switchpoints.add(Switchpoint(newTemp, oldSwitchpoint.timeOfDay))
                markAsModified()
                updateUI()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScheduleEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get zone info from intent
        zoneId = intent.getStringExtra("zone_id")
        zoneName = intent.getStringExtra("zone_name")

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "${zoneName ?: "Unknown Zone"} Schedule"

        // Initialize schedule with empty lists
        daysOfWeek.forEach { day ->
            schedule[day] = mutableListOf()
        }

        // Set current day based on today
        val calendar = Calendar.getInstance()
        val todayIndex = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Convert Sunday=1 to Monday=0
        currentDay = daysOfWeek[todayIndex]

        setupTabs()
        setupRecyclerView()
        setupButtons()
        
        // Load schedule from API
        loadScheduleFromApi()
        
        // Select today's tab
        binding.dayTabs.getTabAt(todayIndex)?.select()
    }

    private fun loadScheduleFromApi() {
        if (zoneId == null) {
            Toast.makeText(this, "Error: No zone ID provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.loadingOverlay.visibility = View.VISIBLE

        scope.launch {
            try {
                // Get auth token
                val sharedPrefs = getSharedPreferences("evohome_prefs", MODE_PRIVATE)
                val accessToken = sharedPrefs.getString("access_token", "") ?: ""
                val authHeader = "bearer $accessToken"

                val response = withContext(Dispatchers.IO) {
                    apiService.getZoneSchedule(zoneId!!, authHeader)
                }

                if (response.isSuccessful && response.body() != null) {
                    val zoneSchedule = response.body()!!
                    
                    // Parse the schedule and format times to HH:MM
                    zoneSchedule.dailySchedules.forEach { dailySchedule ->
                        val formattedSwitchpoints = dailySchedule.switchpoints.map { switchpoint ->
                            Switchpoint(
                                temperature = switchpoint.temperature,
                                timeOfDay = formatTimeToHHMM(switchpoint.timeOfDay)
                            )
                        }.toMutableList()
                        schedule[dailySchedule.dayOfWeek] = formattedSwitchpoints
                    }

                    updateUI()
                } else {
                    throw Exception("Failed to load schedule: ${response.code()}")
                }
                
                binding.loadingOverlay.visibility = View.GONE
            } catch (e: Exception) {
                binding.loadingOverlay.visibility = View.GONE
                handleError("Failed to load schedule", e)
            }
        }
    }

    private fun formatTimeToHHMM(time: String): String {
        // Remove seconds if present (e.g., "06:30:00" -> "06:30")
        return time.substringBeforeLast(":", time)
    }

    private fun setupTabs() {
        daysOfWeek.forEach { day ->
            binding.dayTabs.addTab(binding.dayTabs.newTab().setText(day.take(3)))
        }

        binding.dayTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tab?.let {
                    currentDay = daysOfWeek[it.position]
                    updateUI()
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupRecyclerView() {
        adapter = SwitchpointAdapter(
            onEdit = { position -> editSwitchpoint(position) },
            onDelete = { position -> deleteSwitchpoint(position) }
        )
        binding.switchpointsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.switchpointsRecyclerView.adapter = adapter
    }

    private fun setupButtons() {
        binding.addSwitchpointButton.setOnClickListener {
            showAddSwitchpointDialog()
        }

        binding.bottomAppBar.findViewById<View>(R.id.copyDayButton).setOnClickListener {
            showCopyDayDialog()
        }

        binding.bottomAppBar.findViewById<View>(R.id.saveButton).setOnClickListener {
            saveSchedule()
        }
    }

    private fun updateUI() {
        val switchpoints = schedule[currentDay] ?: mutableListOf()
    
        if (switchpoints.isEmpty()) {
            binding.emptyStateLayout.visibility = View.VISIBLE
            binding.switchpointsRecyclerView.visibility = View.GONE
        } else {
            binding.emptyStateLayout.visibility = View.GONE
            binding.switchpointsRecyclerView.visibility = View.VISIBLE
            adapter.submitList(switchpoints.sortedBy { it.timeOfDay })
        }
    }

    private fun showAddSwitchpointDialog() {
        // Determine a default time (current hour rounded to nearest 30 minutes or next available time)
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(Calendar.MINUTE)
        
        // Round to next 30-minute interval
        val defaultTime = if (currentMinute < 30) {
            String.format("%02d:30", currentHour)
        } else {
            String.format("%02d:00", (currentHour + 1) % 24)
        }
        
        // Launch the full-screen switchpoint editor in "add mode"
        val intent = Intent(this, SwitchpointEditorActivity::class.java).apply {
            putExtra(SwitchpointEditorActivity.EXTRA_TEMPERATURE, 20.0) // Default temperature
            putExtra(SwitchpointEditorActivity.EXTRA_TIME, defaultTime)
            putExtra(SwitchpointEditorActivity.EXTRA_POSITION, -1) // -1 indicates new switchpoint
            putExtra(SwitchpointEditorActivity.EXTRA_IS_NEW, true) // Flag for new switchpoint
        }
        
        switchpointEditorLauncher.launch(intent)
    }

    private fun isValidTimeFormat(time: String): Boolean {
        val regex = Regex("^([0-1][0-9]|2[0-3]):[0-5][0-9]$")
        return regex.matches(time)
    }

    private fun showTimePicker(onTimeSelected: (String) -> Unit) {
        val currentHour = 12
        val currentMinute = 0

        TimePickerDialog(
            this,
            { _, hour, minute ->
                val time = String.format("%02d:%02d", hour, minute)
                onTimeSelected(time)
            },
            currentHour,
            currentMinute,
            true // 24-hour format
        ).show()
    }

    private fun addSwitchpoint(time: String, temperature: Double) {
        val switchpoints = schedule[currentDay] ?: mutableListOf()
        
        // Check if time already exists
        if (switchpoints.any { it.timeOfDay == time }) {
            Toast.makeText(this, "A switchpoint already exists at this time", Toast.LENGTH_SHORT).show()
            return
        }

        switchpoints.add(Switchpoint(temperature, time))
        schedule[currentDay] = switchpoints
        markAsModified()
        updateUI()
    }

    private fun editSwitchpoint(position: Int) {
        val switchpoints = schedule[currentDay] ?: return
        val sortedList = switchpoints.sortedBy { it.timeOfDay }
        val switchpoint = sortedList[position]

        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_switchpoint, null)
        val timeInput = dialogView.findViewById<TextInputEditText>(R.id.timeInput)
        val tempInput = dialogView.findViewById<TextInputEditText>(R.id.tempInput)
        val chipGroup = dialogView.findViewById<com.google.android.material.chip.ChipGroup>(R.id.temperatureChipGroup)

        timeInput.setText(switchpoint.timeOfDay)
        tempInput.setText(switchpoint.temperature.toString())

        timeInput.setOnClickListener {
            showTimePicker { time ->
                timeInput.setText(time)
            }
        }

        // Handle temperature preset chips
        chipGroup.setOnCheckedChangeListener { group, checkedId ->
            when (checkedId) {
                R.id.chip16 -> tempInput.setText("16.0")
                R.id.chip17 -> tempInput.setText("17.0")
                R.id.chip18 -> tempInput.setText("18.0")
                R.id.chip19 -> tempInput.setText("19.0")
                R.id.chip20 -> tempInput.setText("20.0")
                R.id.chip21 -> tempInput.setText("21.0")
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Edit Switchpoint")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val time = timeInput.text.toString()
                val temp = tempInput.text.toString().toDoubleOrNull()

                if (time.isNotEmpty() && temp != null) {
                    if (temp < 5.0 || temp > 35.0) {
                        Toast.makeText(this, "Temperature must be between 5°C and 35°C", Toast.LENGTH_SHORT).show()
                    } else if (!isValidTimeFormat(time)) {
                        Toast.makeText(this, "Please use HH:MM format (e.g., 06:30)", Toast.LENGTH_SHORT).show()
                    } else {
                        switchpoints.remove(switchpoint)
                        switchpoints.add(Switchpoint(temp, time))
                        markAsModified()
                        updateUI()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteSwitchpoint(position: Int) {
        val switchpoints = schedule[currentDay] ?: return
        val sortedList = switchpoints.sortedBy { it.timeOfDay }
        val switchpoint = sortedList[position]

        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Switchpoint")
            .setMessage("Are you sure you want to delete the switchpoint at ${switchpoint.timeOfDay}?")
            .setPositiveButton("Delete") { _, _ ->
                switchpoints.remove(switchpoint)
                markAsModified()
                updateUI()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCopyDayDialog() {
        val currentSwitchpoints = schedule[currentDay]
        if (currentSwitchpoints.isNullOrEmpty()) {
            Toast.makeText(this, "No switchpoints to copy", Toast.LENGTH_SHORT).show()
            return
        }

        val targetDays = daysOfWeek.filter { it != currentDay }
        val checkedItems = BooleanArray(targetDays.size) { false }

        MaterialAlertDialogBuilder(this)
            .setTitle("Copy $currentDay's schedule to:")
            .setMultiChoiceItems(targetDays.toTypedArray(), checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("Copy") { _, _ ->
                var copiedCount = 0
                targetDays.forEachIndexed { index, day ->
                    if (checkedItems[index]) {
                        schedule[day] = currentSwitchpoints.map { 
                            Switchpoint(it.temperature, it.timeOfDay) 
                        }.toMutableList()
                        copiedCount++
                    }
                }
                markAsModified()
                Toast.makeText(this, "Schedule copied to $copiedCount day(s)", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun markAsModified() {
        hasUnsavedChanges = true
        val saveButton = binding.bottomAppBar.findViewById<View>(R.id.saveButton)
        saveButton.isEnabled = true
        saveButton.alpha = 1.0f
    }

    private fun saveSchedule() {
        if (zoneId == null) {
            Toast.makeText(this, "Error: No zone ID", Toast.LENGTH_SHORT).show()
            return
        }

        binding.loadingOverlay.visibility = View.VISIBLE
        binding.saveButton.isEnabled = false

        scope.launch {
            try {
                // Convert schedule to API format
                val dailySchedules = daysOfWeek.map { day ->
                    DailySchedule(
                        dayOfWeek = day,
                        switchpoints = (schedule[day] ?: emptyList()).sortedBy { it.timeOfDay }
                    )
                }

                val zoneSchedule = ZoneSchedule(dailySchedules)

                // Get auth token
                val sharedPrefs = getSharedPreferences("evohome_prefs", MODE_PRIVATE)
                val accessToken = sharedPrefs.getString("access_token", "") ?: ""
                val authHeader = "bearer $accessToken"

                // Save to API
                withContext(Dispatchers.IO) {
                    apiService.updateZoneSchedule(zoneId!!, authHeader, schedule = zoneSchedule)
                }

                binding.loadingOverlay.visibility = View.GONE
                hasUnsavedChanges = false

                // Reset save button state
                val saveButton = binding.bottomAppBar.findViewById<View>(R.id.saveButton)
                saveButton.alpha = 0.5f
                saveButton.isEnabled = false

                Toast.makeText(this@ScheduleEditorActivity, "Schedule saved successfully!", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                binding.loadingOverlay.visibility = View.GONE
                binding.saveButton.isEnabled = true
                handleError("Failed to save schedule", e)
            }
        }
    }

    private fun handleError(message: String, e: Exception) {
        val errorDetail = when (e) {
            is HttpException -> "HTTP ${e.code()}: ${e.message()}"
            else -> e.message ?: "Unknown error"
        }
        
        Toast.makeText(this, "$message: $errorDetail", Toast.LENGTH_LONG).show()
        e.printStackTrace()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (hasUnsavedChanges) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Unsaved Changes")
                .setMessage("You have unsaved changes. Are you sure you want to leave?")
                .setPositiveButton("Leave") { _, _ ->
                    finish()
                }
                .setNegativeButton("Stay", null)
                .show()
        } else {
            finish()
        }
        return true
    }

    override fun onBackPressed() {
        if (hasUnsavedChanges) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Unsaved Changes")
                .setMessage("You have unsaved changes. Are you sure you want to leave?")
                .setPositiveButton("Leave") { _, _ ->
                    super.onBackPressed()
                }
                .setNegativeButton("Stay", null)
                .show()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}

class SwitchpointAdapter(
    private val onEdit: (Int) -> Unit,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<SwitchpointAdapter.SwitchpointViewHolder>() {

    private var switchpoints = listOf<Switchpoint>()

    fun submitList(list: List<Switchpoint>) {
        switchpoints = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SwitchpointViewHolder {
        val binding = ItemSwitchpointBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SwitchpointViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SwitchpointViewHolder, position: Int) {
        holder.bind(switchpoints[position], position)
    }

    override fun getItemCount() = switchpoints.size

    inner class SwitchpointViewHolder(
        private val binding: ItemSwitchpointBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(switchpoint: Switchpoint, position: Int) {
            val tempFormat = DecimalFormat("0.0")
            binding.timeText.text = switchpoint.timeOfDay
            binding.temperatureText.text = "${tempFormat.format(switchpoint.temperature)}°"

            // Use official Evohome teal/green color for temperature block
            // You can adjust this based on temperature if desired
            val tempColor = when {
                switchpoint.temperature >= 20.0 -> Color.parseColor("#E8734E") // Warmer teal-orange
                switchpoint.temperature >= 18.0 -> Color.parseColor("#3D9B8F") // Standard teal
                switchpoint.temperature >= 16.0 -> Color.parseColor("#3D9B8F") // Standard teal
                else -> Color.parseColor("#5BA8B8") // Cooler blue-teal
            }
            
            binding.temperatureText.setBackgroundColor(tempColor)

            // Make the entire item tappable - opens edit dialog
            binding.switchpointContainer.setOnClickListener {
                showEditOptions(position)
            }
        }
        
        private fun showEditOptions(position: Int) {
            // Get the activity context
            val activity = binding.root.context as? ScheduleEditorActivity ?: return
            
            // Access schedule and currentDay from the activity
            val switchpoints = activity.schedule[activity.currentDay] ?: return
            val sortedList = switchpoints.sortedBy { it.timeOfDay }
            
            if (position < 0 || position >= sortedList.size) return
            
            val switchpoint = sortedList[position]
            
            // Launch the full-screen switchpoint editor
            val intent = Intent(activity, SwitchpointEditorActivity::class.java).apply {
                putExtra(SwitchpointEditorActivity.EXTRA_TEMPERATURE, switchpoint.temperature)
                putExtra(SwitchpointEditorActivity.EXTRA_TIME, switchpoint.timeOfDay)
                putExtra(SwitchpointEditorActivity.EXTRA_POSITION, position)
            }
            
            activity.switchpointEditorLauncher.launch(intent)
        }
    }
}