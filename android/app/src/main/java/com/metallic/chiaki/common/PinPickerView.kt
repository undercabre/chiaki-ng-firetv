// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.metallic.chiaki.R

/**
 * PIN entry without keyboard: one box per digit.
 * D-pad up/down changes the digit, left/right moves between boxes,
 * the number keys of a remote type directly. Works with touch too (tap = +1).
 */
class PinPickerView @JvmOverloads constructor(
	context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
): LinearLayout(context, attrs, defStyleAttr)
{
	private val digits = mutableListOf<TextView>()
	private val values = mutableListOf<Int>()

	var onPinChanged: ((String) -> Unit)? = null

	init
	{
		orientation = HORIZONTAL
		gravity = Gravity.CENTER
		val attrLength = context.obtainStyledAttributes(attrs, R.styleable.PinPickerView).let {
			val l = it.getInt(R.styleable.PinPickerView_pinLength, 8)
			it.recycle()
			l
		}
		setLength(attrLength)
	}

	val pin: String get() = values.joinToString("")

	fun setLength(length: Int)
	{
		removeAllViews()
		digits.clear()
		values.clear()
		for(i in 0 until length)
		{
			values.add(0)
			val tv = TextView(context).apply {
				text = "0"
				setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
				setTextColor(Color.WHITE)
				gravity = Gravity.CENTER
				isFocusable = true
				isFocusableInTouchMode = false
				background = digitBackground()
				val size = dp(56)
				layoutParams = LayoutParams(dp(44), size).also { lp -> lp.setMargins(dp(4), dp(4), dp(4), dp(4)) }
				setOnKeyListener { _, keyCode, event -> onDigitKey(i, keyCode, event) }
				setOnClickListener { change(i, 1) }
			}
			digits.add(tv)
			addView(tv)
		}
	}

	/** Fill the boxes from a string of digits (e.g. received from the phone link). */
	fun setPin(pin: String)
	{
		val digitsOnly = pin.filter { it.isDigit() }
		for(i in values.indices)
		{
			val v = digitsOnly.getOrNull(i)?.digitToInt() ?: 0
			values[i] = v
			digits[i].text = v.toString()
		}
		onPinChanged?.invoke(this.pin)
	}

	fun clear()
	{
		for(i in values.indices)
		{
			values[i] = 0
			digits[i].text = "0"
		}
		onPinChanged?.invoke(pin)
	}

	override fun requestFocus(direction: Int, previouslyFocusedRect: android.graphics.Rect?): Boolean =
		digits.firstOrNull()?.requestFocus() ?: super.requestFocus(direction, previouslyFocusedRect)

	private fun onDigitKey(index: Int, keyCode: Int, event: KeyEvent): Boolean
	{
		if(event.action != KeyEvent.ACTION_DOWN)
			return keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ||
					keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
		when(keyCode)
		{
			KeyEvent.KEYCODE_DPAD_UP -> { change(index, 1); return true }
			KeyEvent.KEYCODE_DPAD_DOWN -> { change(index, -1); return true }
			in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
				set(index, keyCode - KeyEvent.KEYCODE_0)
				focusDigit(index + 1)
				return true
			}
			KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
				// Cross on a digit: jump to the next one, or leave the picker on the last one.
				if(index + 1 < digits.size)
				{
					focusDigit(index + 1)
					return true
				}
				return false
			}
		}
		return false
	}

	private fun focusDigit(index: Int)
	{
		if(index in digits.indices)
			digits[index].requestFocus()
		else
			digits.last().focusSearch(View.FOCUS_DOWN)?.requestFocus()
	}

	private fun change(index: Int, delta: Int) = set(index, (values[index] + delta + 10) % 10)

	private fun set(index: Int, value: Int)
	{
		values[index] = value
		digits[index].text = value.toString()
		onPinChanged?.invoke(pin)
	}

	private fun digitBackground(): StateListDrawable
	{
		fun box(strokeColor: Int, fill: Int) = GradientDrawable().apply {
			cornerRadius = dp(8).toFloat()
			setColor(fill)
			setStroke(dp(3), strokeColor)
		}
		val accent = ContextCompat.getColor(context, R.color.accent)
		return StateListDrawable().apply {
			addState(intArrayOf(android.R.attr.state_focused), box(accent, 0x55000000))
			addState(intArrayOf(), box(0x66FFFFFF, 0x33000000))
		}
	}

	private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
