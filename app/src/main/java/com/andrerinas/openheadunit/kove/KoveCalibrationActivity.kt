package com.andrerinas.openheadunit.kove

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.andrerinas.openheadunit.R

/**
 * Lines the Android Auto picture up with the part of the TFT the motorcycle leaves visible.
 * While this screen is open a green frame on the TFT shows the edges of that area; each slider
 * moves one edge and the change is applied to the running session straight away.
 */
class KoveCalibrationActivity : Activity() {

    private lateinit var topLabel: TextView
    private lateinit var bottomLabel: TextView
    private lateinit var topBar: SeekBar
    private lateinit var bottomBar: SeekBar
    private lateinit var widthLabel: TextView
    private lateinit var widthBar: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KoveSettings.load(this)

        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (32 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt())
            setBackgroundColor(Color.parseColor("#101418"))
        }
        fun text(value: String, size: Float, color: String = "#E6E6E6") = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.parseColor(color))
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        }

        root.addView(text(getString(R.string.kove_calibration_title), 22f, "#FFFFFF"))
        root.addView(text(getString(R.string.kove_calibration_help), 15f, "#B0BEC5"))

        topLabel = text("", 17f)
        topBar = slider(KoveSettings.hiddenTop, KoveSettings.MAX_HIDDEN)
        bottomLabel = text("", 17f)
        bottomBar = slider(KoveSettings.hiddenBottom - KoveSettings.MIN_BOTTOM, KoveSettings.MAX_HIDDEN - KoveSettings.MIN_BOTTOM)
        root.addView(topLabel)
        root.addView(topBar)
        root.addView(bottomLabel)
        root.addView(bottomBar)
        widthLabel = text("", 17f)
        widthBar = slider(KoveSettings.widthPercent - KoveSettings.MIN_WIDTH, KoveSettings.MAX_WIDTH - KoveSettings.MIN_WIDTH)
        root.addView(widthLabel)
        root.addView(widthBar)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, (24 * dp).toInt(), 0, 0)
        }
        buttons.addView(Button(this).apply {
            text = getString(R.string.kove_calibration_reset)
            setOnClickListener {
                topBar.progress = KoveSettings.DEFAULT_TOP
                bottomBar.progress = KoveSettings.DEFAULT_BOTTOM - KoveSettings.MIN_BOTTOM
                widthBar.progress = KoveSettings.DEFAULT_WIDTH - KoveSettings.MIN_WIDTH
                apply()
            }
        })
        buttons.addView(Button(this).apply {
            text = getString(R.string.kove_calibration_done)
            setOnClickListener { finish() }
        })
        root.addView(buttons)
        setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        updateLabels()
    }

    override fun onResume() {
        super.onResume()
        KoveSettings.calibrating = true
    }

    override fun onPause() {
        super.onPause()
        KoveSettings.calibrating = false
    }

    private fun slider(value: Int, range: Int) = SeekBar(this).apply {
        max = range
        progress = value
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                updateLabels()
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}

            // Re-laying out Android Auto takes a moment, so apply when the finger lifts.
            override fun onStopTrackingTouch(bar: SeekBar) = apply()
        })
    }

    private fun apply() {
        KoveSettings.save(this, topBar.progress, bottomValue(), KoveSettings.MIN_WIDTH + widthBar.progress)
        KoveBridge.applyTftInsets()
    }

    private fun bottomValue() = bottomBar.progress + KoveSettings.MIN_BOTTOM

    private fun updateLabels() {
        if (!::widthBar.isInitialized) return
        topLabel.text = getString(R.string.kove_calibration_top, topBar.progress)
        val bottom = bottomValue()
        bottomLabel.text = if (bottom >= 0) getString(R.string.kove_calibration_bottom, bottom)
        else getString(R.string.kove_calibration_bottom_cut, -bottom)
        widthLabel.text = getString(R.string.kove_calibration_width, KoveSettings.MIN_WIDTH + widthBar.progress)
    }

    override fun finish() {
        if (::widthBar.isInitialized) apply()
        super.finish()
    }
}
