// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.content.Context
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R

/**
 * Free bitrate entry without a keyboard (2 to 100 Mbps):
 * D-pad ← → = ±1 Mbps, L1 / R1 = ±10 Mbps, remote number keys type the value.
 * [onResult] receives the bitrate in kbps, or null for automatic.
 */
object BitrateDialog
{
	private const val MIN_MBPS = Preferences.BITRATE_MIN_KBPS / 1000
	private const val MAX_MBPS = Preferences.BITRATE_MAX_KBPS / 1000

	fun show(context: Context, title: CharSequence, currentKbps: Int?, autoKbps: Int, onResult: (Int?) -> Unit): AlertDialog
	{
		val density = context.resources.displayMetrics.density
		fun dp(v: Int) = (v * density).toInt()

		val startMbps = ((currentKbps ?: autoKbps) / 1000).coerceIn(MIN_MBPS, MAX_MBPS)

		val valueText = TextView(context).apply {
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
			gravity = Gravity.CENTER
		}
		val helpText = TextView(context).apply {
			text = context.getString(R.string.bitrate_dialog_help, autoKbps / 1000)
			gravity = Gravity.CENTER
			alpha = 0.75f
		}
		val seekBar = SeekBar(context).apply {
			max = MAX_MBPS - MIN_MBPS
			progress = startMbps - MIN_MBPS
			keyProgressIncrement = 1
			isFocusable = true
		}
		fun update() { valueText.text = context.getString(R.string.preferences_bitrate_value, seekBar.progress + MIN_MBPS) }
		update()
		seekBar.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
			override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = update()
			override fun onStartTrackingTouch(seekBar: SeekBar) {}
			override fun onStopTrackingTouch(seekBar: SeekBar) {}
		})

		// L1/R1 big steps and typing digits with the remote
		var typed = ""
		var typedAt = 0L
		seekBar.setOnKeyListener { _, _, event ->
			val logical = Gamepad.logicalKeyCode(event)
			val isStep = logical == KeyEvent.KEYCODE_BUTTON_L1 || logical == KeyEvent.KEYCODE_BUTTON_R1
			val isDigit = event.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9
			if(!isStep && !isDigit)
				return@setOnKeyListener false
			if(event.action != KeyEvent.ACTION_DOWN)
				return@setOnKeyListener true
			if(isStep)
				seekBar.progress = (seekBar.progress + if(logical == KeyEvent.KEYCODE_BUTTON_R1) 10 else -10)
					.coerceIn(0, seekBar.max)
			else
			{
				val now = SystemClock.uptimeMillis()
				if(now - typedAt > 1500)
					typed = ""
				typedAt = now
				typed = (typed + (event.keyCode - KeyEvent.KEYCODE_0)).takeLast(3)
				typed.toIntOrNull()?.let { seekBar.progress = (it - MIN_MBPS).coerceIn(0, seekBar.max) }
			}
			true
		}

		val layout = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(24), dp(16), dp(24), dp(8))
			addView(valueText)
			addView(seekBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
			addView(helpText)
		}

		val dialog = MaterialAlertDialogBuilder(context)
			.setTitle(title)
			.setView(layout)
			.setPositiveButton(android.R.string.ok) { _, _ -> onResult((seekBar.progress + MIN_MBPS) * 1000) }
			.setNeutralButton(R.string.bitrate_dialog_auto) { _, _ -> onResult(null) }
			.setNegativeButton(android.R.string.cancel) { _, _ -> }
			.create()
			.withGamepadNav()
		dialog.show()
		seekBar.requestFocus()
		return dialog
	}
}
