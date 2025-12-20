package com.yourname.evohomecontrol

import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yourname.evohomecontrol.databinding.ActivitySwitchpointEditorBinding
import java.text.DecimalFormat
import kotlin.math.abs

class SwitchpointEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySwitchpointEditorBinding
    private var currentTemperature: Double = 18.5
    private var originalTemperature: Double = 18.5
    private val tempFormat = DecimalFormat("0.0")
    private lateinit var gestureDetector: GestureDetector
    private var hasChanges = false

    private var isNewSwitchpoint = false
    private var currentTime: String = "00:00"
    private var originalTime: String = "00:00"

    // Intent extras keys
    companion object {
        const val EXTRA_TEMPERATURE = "temperature"
        const val EXTRA_TIME = "time"
        const val EXTRA_POSITION = "position"
        const val EXTRA_IS_NEW = "is_new"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySwitchpointEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get data from intent
        currentTemperature = intent.getDoubleExtra(EXTRA_TEMPERATURE, 18.5)
        originalTemperature = currentTemperature
        currentTime = intent.getStringExtra(EXTRA_TIME) ?: "00:00"
        originalTime = currentTime
        isNewSwitchpoint = intent.getBooleanExtra(EXTRA_IS_NEW, false)

        // Set initial values
        updateTemperatureDisplay()
        binding.timeText.text = currentTime

        // Make time clickable for new switchpoints
        if (isNewSwitchpoint) {
            binding.timeDisplay.setOnClickListener {
                showTimePicker()
            }
            binding.timeDisplay.foreground = getDrawable(android.R.drawable.list_selector_background)
        }

        // Setup gesture detector for swipe up/down
        gestureDetector = GestureDetector(this, SwipeGestureListener())

        // Setup click listeners
        setupListeners()
    }

    private fun showTimePicker() {
        val timeParts = currentTime.split(":")
        val currentHour = timeParts.getOrNull(0)?.toIntOrNull() ?: 12
        val currentMinute = timeParts.getOrNull(1)?.toIntOrNull() ?: 0

        android.app.TimePickerDialog(
            this,
            { _, hour, minute ->
                currentTime = String.format("%02d:%02d", hour, minute)
                binding.timeText.text = currentTime
                markAsModified()
            },
            currentHour,
            currentMinute,
            true // 24-hour format
        ).show()
    }

    private fun setupListeners() {
        // Temperature container - handle touch events
        var startY = 0f
        var lastY = 0f
        var isDragging = false

        binding.temperatureContainer.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.y
                    lastY = event.y
                    isDragging = false
                }
                
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = lastY - event.y
                    
                    // If moved more than 10 pixels, consider it a drag
                    if (kotlin.math.abs(event.y - startY) > 10) {
                        isDragging = true
                        
                        // For every 50 pixels of movement, change temperature
                        if (kotlin.math.abs(deltaY) > 50) {
                            if (deltaY > 0) {
                                // Dragged up - increase temperature
                                increaseTemperature()
                            } else {
                                // Dragged down - decrease temperature
                                decreaseTemperature()
                            }
                            lastY = event.y
                        }
                    }
                }
                
                MotionEvent.ACTION_UP -> {
                    // If not dragging, treat as tap
                    if (!isDragging) {
                        val y = event.y
                        val containerHeight = view.height
                        
                        // Tap in upper half = increase
                        if (y < containerHeight / 2) {
                            increaseTemperature()
                        } 
                        // Tap in lower half = decrease
                        else {
                            decreaseTemperature()
                        }
                    }
                }
            }
            true
        }

        // Close button
        binding.closeButton.setOnClickListener {
            handleClose()
        }

        // Save text
        binding.saveText.setOnClickListener {
            saveAndFinish()
        }

        // Delete button
        binding.deleteButton.setOnClickListener {
            confirmDelete()
        }

        // Info button
        binding.infoButton.setOnClickListener {
            showInfoDialog()
        }

        // Hide delete button for new switchpoints
        if (isNewSwitchpoint) {
            binding.deleteButton.visibility = View.GONE
        }
    }

    private fun increaseTemperature() {
        if (currentTemperature < 35.0) {
            currentTemperature += 0.5
            updateTemperatureDisplay()
            markAsModified()
        } else {
            Toast.makeText(this, "Maximum temperature is 35°C", Toast.LENGTH_SHORT).show()
        }
    }

    private fun decreaseTemperature() {
        if (currentTemperature > 5.0) {
            currentTemperature -= 0.5
            updateTemperatureDisplay()
            markAsModified()
        } else {
            Toast.makeText(this, "Minimum temperature is 5°C", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateTemperatureDisplay() {
        binding.temperatureValue.text = "${tempFormat.format(currentTemperature)}°"
        
        // Change background color based on temperature
        val backgroundColor = when {
            currentTemperature >= 20.0 -> android.graphics.Color.parseColor("#E8734E")
            currentTemperature >= 17.0 -> android.graphics.Color.parseColor("#3D9B8F")
            else -> android.graphics.Color.parseColor("#5BA8B8")
        }
        binding.root.setBackgroundColor(backgroundColor)
    }

    private fun markAsModified() {
        val hasTemperatureChange = currentTemperature != originalTemperature
        val hasTimeChange = isNewSwitchpoint && currentTime != originalTime
        
        if ((hasTemperatureChange || hasTimeChange) && !hasChanges) {
            hasChanges = true
            binding.saveText.visibility = View.VISIBLE
        } else if (!hasTemperatureChange && !hasTimeChange && hasChanges) {
            hasChanges = false
            binding.saveText.visibility = View.GONE
        }
    }

    private fun handleClose() {
        if (hasChanges) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Unsaved Changes")
                .setMessage("You have unsaved changes. Do you want to save before closing?")
                .setPositiveButton("Save") { _, _ ->
                    saveAndFinish()
                }
                .setNegativeButton("Discard") { _, _ ->
                    setResult(RESULT_CANCELED)
                    finish()
                }
                .setNeutralButton("Cancel", null)
                .show()
        } else {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun saveAndFinish() {
        val resultIntent = intent.apply {
            putExtra(EXTRA_TEMPERATURE, currentTemperature)
            putExtra(EXTRA_TIME, currentTime)
            putExtra(EXTRA_IS_NEW, isNewSwitchpoint)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Switchpoint")
            .setMessage("Are you sure you want to delete this switchpoint?")
            .setPositiveButton("Delete") { _, _ ->
                val resultIntent = intent.apply {
                    putExtra("delete", true)
                }
                setResult(RESULT_OK, resultIntent)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showInfoDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Switchpoint Editor")
            .setMessage("Tap or drag up to increase temperature.\nTap or drag down to decrease temperature.\n\nTemperature range: 5°C - 35°C\nAdjustment: 0.5°C increments")
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onBackPressed() {
        handleClose()
    }

    // Gesture detector for swipe up/down
    private inner class SwipeGestureListener : GestureDetector.SimpleOnGestureListener() {
        private val SWIPE_THRESHOLD = 50
        private val SWIPE_VELOCITY_THRESHOLD = 50

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (e1 == null) return false
            
            val diffY = e2.y - e1.y
            
            if (abs(diffY) > SWIPE_THRESHOLD && abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                if (diffY < 0) {
                    // Swipe up - increase temperature
                    increaseTemperature()
                } else {
                    // Swipe down - decrease temperature
                    decreaseTemperature()
                }
                return true
            }
            return false
        }
    }
}
