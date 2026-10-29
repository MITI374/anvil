package com.andrerinas.openheadunit.kove

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
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
    private lateinit var badgeSizeLabel: TextView
    private lateinit var badgeSizeBar: SeekBar

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
        widthLabel = text("", 17f)
        widthBar = slider(KoveSettings.widthPercent - KoveSettings.MIN_WIDTH, KoveSettings.MAX_WIDTH - KoveSettings.MIN_WIDTH)

        // Each setting: tap the label to type the value; the buttons nudge it; the slider drags it.
        addSetting(root, topLabel, topBar, 0, R.string.kove_calibration_top_short)
        addSetting(root, bottomLabel, bottomBar, KoveSettings.MIN_BOTTOM, R.string.kove_calibration_bottom_short)
        addSetting(root, widthLabel, widthBar, KoveSettings.MIN_WIDTH, R.string.kove_calibration_width_short)

        badgeSizeLabel = text("", 17f)
        badgeSizeBar = slider(KoveSettings.badgeTextSize - KoveSettings.MIN_BADGE_SIZE, KoveSettings.MAX_BADGE_SIZE - KoveSettings.MIN_BADGE_SIZE)
        addSetting(root, badgeSizeLabel, badgeSizeBar, KoveSettings.MIN_BADGE_SIZE, R.string.kove_badge_size_short)

        val badgeNames = resources.getStringArray(R.array.kove_badge_positions)
        root.addView(Button(this).apply {
            fun label() { text = getString(R.string.kove_badge_button, badgeNames[KoveSettings.badgeCorner]) }
            label()
            setOnClickListener {
                KoveSettings.saveBadge(this@KoveCalibrationActivity, (KoveSettings.badgeCorner + 1) % 5)
                label()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (20 * dp).toInt()
        })

        root.addView(Button(this).apply {
            text = getString(R.string.kove_join_wifi)
            setOnClickListener { joinWifi() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (20 * dp).toInt()
        })

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
        // The help text is long on a phone: let the whole screen scroll so every slider and button is reachable.
        val scroller = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#101418"))
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroller, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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

    /** Label (tap to type), slider, and a row of nudge buttons. [offset] maps slider position to value. */
    private fun addSetting(root: LinearLayout, label: TextView, bar: SeekBar, offset: Int, titleRes: Int) {
        label.setOnClickListener { askValue(bar, offset, titleRes) }
        root.addView(label)
        root.addView(bar)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (step in intArrayOf(-10, -1, 1, 10)) {
            row.addView(Button(this).apply {
                text = if (step > 0) "+$step" else "$step"
                minWidth = 0
                minimumWidth = 0
                setOnClickListener {
                    bar.progress = (bar.progress + step).coerceIn(0, bar.max)
                    updateLabels()
                    apply()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(row)
    }

    private fun askValue(bar: SeekBar, offset: Int, titleRes: Int) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText((bar.progress + offset).toString())
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(getString(R.string.kove_calibration_range, offset, bar.max + offset))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = input.text.toString().trim().toIntOrNull() ?: return@setPositiveButton
                bar.progress = (value - offset).coerceIn(0, bar.max)
                updateLabels()
                apply()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun joinWifi() {
        val msg = when (KoveBridge.joinTftWifiNow()) {
            KoveBridge.JoinResult.REQUESTED -> R.string.kove_join_wifi_requested
            KoveBridge.JoinResult.ALREADY_CONNECTED -> R.string.kove_join_wifi_connected
            KoveBridge.JoinResult.NO_SESSION -> R.string.kove_join_wifi_no_session
            KoveBridge.JoinResult.NAME_UNKNOWN -> R.string.kove_join_wifi_unknown
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
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
        if (::badgeSizeBar.isInitialized) KoveSettings.saveBadgeSize(this, KoveSettings.MIN_BADGE_SIZE + badgeSizeBar.progress)
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
        if (::badgeSizeBar.isInitialized) {
            badgeSizeLabel.text = getString(R.string.kove_badge_size, KoveSettings.MIN_BADGE_SIZE + badgeSizeBar.progress)
        }
    }

    override fun finish() {
        if (::widthBar.isInitialized) apply()
        super.finish()
    }
}
