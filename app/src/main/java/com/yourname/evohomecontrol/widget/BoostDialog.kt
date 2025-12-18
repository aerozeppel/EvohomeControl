package com.yourname.evohomecontrol.widget

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Window
import android.widget.Button
import android.widget.TextView
import com.yourname.evohomecontrol.R

class BoostDialog(
    context: Context,
    private val zoneName: String,
    private val currentTemp: Double,
    private val callback: (Double) -> Unit
) : Dialog(context) {

    private var tempOffset = 0.5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_widget_boost)

        val dialogTitle = findViewById<TextView>(R.id.dialogTitle)
        val dialogZoneName = findViewById<TextView>(R.id.dialogZoneName)
        val tempOffsetTv = findViewById<TextView>(R.id.tempOffset)
        val btnMinus = findViewById<Button>(R.id.btnMinus)
        val btnPlus = findViewById<Button>(R.id.btnPlus)
        val btnCancel = findViewById<Button>(R.id.btnCancel)
        val btnBoost = findViewById<Button>(R.id.btnBoost)

        dialogTitle.text = "Boost Zone"
        dialogZoneName.text = zoneName
        updateTempOffset(tempOffsetTv)

        btnMinus.setOnClickListener {
            if (currentTemp + tempOffset > 5.0) { // Assuming 5.0 C is the minimum
                tempOffset -= 0.5
                updateTempOffset(tempOffsetTv)
            }
        }

        btnPlus.setOnClickListener {
            if (currentTemp + tempOffset < 32.0) { // Assuming 32.0 C is the maximum
                tempOffset += 0.5
                updateTempOffset(tempOffsetTv)
            }
        }

        btnCancel.setOnClickListener {
            dismiss()
        }

        btnBoost.setOnClickListener {
            callback(currentTemp + tempOffset)
            dismiss()
        }
    }

    private fun updateTempOffset(textView: TextView) {
        textView.text = String.format("%+.1f°C", tempOffset)
    }
}