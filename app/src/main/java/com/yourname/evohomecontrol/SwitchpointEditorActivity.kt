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

    // Intent extras keys
    companion object {
        const val EXTRA_TEMPERATURE = "temperature"
        const val EXTRA_TIME = "time"
        const val EXTRA_POSITION = "position"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySwitchpointEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get data from intent
        currentTemperature = intent.getDoubleExtra(EXTRA_TEMPERATURE, 18.5)
        originalTemperature = currentTemperature
        val timeString = intent.getStringExtra(EXTRA_TIME) ?: "00:00"

        // Set initial values
        updateTemperatureDisplay()
        binding.timeText.text = timeString

        // Setup gesture detector for swipe up/down
        gestureDetector = GestureDetector(this, SwipeGestureListener())

        // Setup click listeners
        setupListeners()
    }

    private fun setupListeners() {
        // Temperature container - handle touch events
        binding.temperatureContainer.setOnTouchListener { view, event ->
            gestureDetector.onTouchEvent(event)
            
            // Also handle simple taps
            if (event.action == MotionEvent.ACTION_UP) {
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
        if (currentTemperature != originalTemperature && !hasChanges) {
            hasChanges = true
            binding.saveText.visibility = View.VISIBLE
        } else if (currentTemperature == originalTemperature && hasChanges) {
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
