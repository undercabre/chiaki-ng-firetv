// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.app.Dialog
import android.content.Context
import android.util.SparseBooleanArray
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.preference.PreferenceManager
import com.metallic.chiaki.R

/**
 * Fire TV helpers for game controllers.
 *
 * Fire OS kernels often lack the hid-playstation driver. In that case a DualSense (or DS4)
 * is handled by hid-generic and Android reports "raw" HID buttons in the wrong order
 * (Cross arrives as BUTTON_B, Circle as BUTTON_C, L2 as BUTTON_L1, ...) and the triggers
 * on AXIS_RX / AXIS_RY. This object translates everything into one "logical" space that
 * uses the standard Android gamepad key codes:
 *   BUTTON_A = Cross, BUTTON_B = Circle, BUTTON_X = Square, BUTTON_Y = Triangle,
 *   SELECT = Share/Create, START = Options, MODE = PS, KEYCODE_TOUCHPAD = touchpad click.
 */
object Gamepad
{
	const val SONY_VENDOR_ID = 0x054C
	/** Pseudo key code for the touchpad click, never dispatched to Android views. */
	const val KEYCODE_TOUCHPAD = 10000

	enum class Mapping(val value: String)
	{
		AUTO("auto"),
		STANDARD("standard"),
		SONY_RAW("sony_raw");

		companion object
		{
			fun fromValue(value: String?) = values().firstOrNull { it.value == value } ?: AUTO
		}
	}

	@Volatile var mapping = Mapping.AUTO
		private set
	@Volatile var swapCrossMoon = false
		private set

	private val rawCache = SparseBooleanArray()

	/** Re-read the controller related preferences. Cheap, call it in onResume(). */
	fun loadSettings(context: Context)
	{
		val prefs = PreferenceManager.getDefaultSharedPreferences(context)
		mapping = Mapping.fromValue(prefs.getString(context.getString(R.string.preferences_controller_mapping_key), Mapping.AUTO.value))
		swapCrossMoon = prefs.getBoolean(context.getString(R.string.preferences_swap_cross_moon_key), false)
		synchronized(rawCache) { rawCache.clear() }
	}

	fun isGamepadSource(source: Int) =
		source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
				source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK

	fun isGamepad(device: InputDevice?) = device != null && isGamepadSource(device.sources)

	/** Heuristic: a Sony pad without trigger axes but with RX/RY is driven by hid-generic. */
	fun looksLikeRawSony(device: InputDevice): Boolean
	{
		if(device.vendorId != SONY_VENDOR_ID)
			return false
		val hasTriggerAxis = device.getMotionRange(MotionEvent.AXIS_LTRIGGER) != null ||
				device.getMotionRange(MotionEvent.AXIS_BRAKE) != null
		val hasRxRy = device.getMotionRange(MotionEvent.AXIS_RX) != null &&
				device.getMotionRange(MotionEvent.AXIS_RY) != null
		return !hasTriggerAxis && hasRxRy
	}

	fun isRaw(device: InputDevice?): Boolean
	{
		if(device == null || !isGamepad(device))
			return false
		return when(mapping)
		{
			Mapping.STANDARD -> false
			Mapping.SONY_RAW -> true
			Mapping.AUTO -> synchronized(rawCache) {
				val idx = rawCache.indexOfKey(device.id)
				if(idx >= 0)
					rawCache.valueAt(idx)
				else
					looksLikeRawSony(device).also { rawCache.put(device.id, it) }
			}
		}
	}

	private val rawKeyTable = mapOf(
		// hid-generic order of a DualSense / DS4: Square, Cross, Circle, Triangle, L1, R1, L2, R2,
		// Share, Options, L3, R3, PS, Touchpad -> BTN_A, BTN_B, BTN_C, BTN_X, BTN_Y, BTN_Z, BTN_TL, ...
		KeyEvent.KEYCODE_BUTTON_A to KeyEvent.KEYCODE_BUTTON_X,
		KeyEvent.KEYCODE_BUTTON_B to KeyEvent.KEYCODE_BUTTON_A,
		KeyEvent.KEYCODE_BUTTON_C to KeyEvent.KEYCODE_BUTTON_B,
		KeyEvent.KEYCODE_BUTTON_X to KeyEvent.KEYCODE_BUTTON_Y,
		KeyEvent.KEYCODE_BUTTON_Y to KeyEvent.KEYCODE_BUTTON_L1,
		KeyEvent.KEYCODE_BUTTON_Z to KeyEvent.KEYCODE_BUTTON_R1,
		KeyEvent.KEYCODE_BUTTON_L1 to KeyEvent.KEYCODE_BUTTON_L2,
		KeyEvent.KEYCODE_BUTTON_R1 to KeyEvent.KEYCODE_BUTTON_R2,
		KeyEvent.KEYCODE_BUTTON_L2 to KeyEvent.KEYCODE_BUTTON_SELECT,
		KeyEvent.KEYCODE_BUTTON_R2 to KeyEvent.KEYCODE_BUTTON_START,
		KeyEvent.KEYCODE_BUTTON_SELECT to KeyEvent.KEYCODE_BUTTON_THUMBL,
		KeyEvent.KEYCODE_BUTTON_START to KeyEvent.KEYCODE_BUTTON_THUMBR,
		KeyEvent.KEYCODE_BUTTON_MODE to KeyEvent.KEYCODE_BUTTON_MODE,
		KeyEvent.KEYCODE_BUTTON_THUMBL to KEYCODE_TOUCHPAD,
		// Same order when the device is classified as a joystick (BTN_TRIGGER...)
		KeyEvent.KEYCODE_BUTTON_1 to KeyEvent.KEYCODE_BUTTON_X,
		KeyEvent.KEYCODE_BUTTON_2 to KeyEvent.KEYCODE_BUTTON_A,
		KeyEvent.KEYCODE_BUTTON_3 to KeyEvent.KEYCODE_BUTTON_B,
		KeyEvent.KEYCODE_BUTTON_4 to KeyEvent.KEYCODE_BUTTON_Y,
		KeyEvent.KEYCODE_BUTTON_5 to KeyEvent.KEYCODE_BUTTON_L1,
		KeyEvent.KEYCODE_BUTTON_6 to KeyEvent.KEYCODE_BUTTON_R1,
		KeyEvent.KEYCODE_BUTTON_7 to KeyEvent.KEYCODE_BUTTON_L2,
		KeyEvent.KEYCODE_BUTTON_8 to KeyEvent.KEYCODE_BUTTON_R2,
		KeyEvent.KEYCODE_BUTTON_9 to KeyEvent.KEYCODE_BUTTON_SELECT,
		KeyEvent.KEYCODE_BUTTON_10 to KeyEvent.KEYCODE_BUTTON_START,
		KeyEvent.KEYCODE_BUTTON_11 to KeyEvent.KEYCODE_BUTTON_THUMBL,
		KeyEvent.KEYCODE_BUTTON_12 to KeyEvent.KEYCODE_BUTTON_THUMBR,
		KeyEvent.KEYCODE_BUTTON_13 to KeyEvent.KEYCODE_BUTTON_MODE,
		KeyEvent.KEYCODE_BUTTON_14 to KEYCODE_TOUCHPAD
	)

	/** Logical key code (see class doc) for a key event. Non gamepad events are returned unchanged. */
	fun logicalKeyCode(event: KeyEvent): Int
	{
		if(!isGamepadSource(event.source) || !isRaw(event.device))
			return event.keyCode
		return rawKeyTable[event.keyCode] ?: event.keyCode
	}

	data class Sticks(
		val leftX: Float, val leftY: Float,
		val rightX: Float, val rightY: Float,
		val l2: Float, val r2: Float,
		val hatX: Float, val hatY: Float)

	/** Read sticks and triggers in logical space. Triggers are normalized to 0..1. */
	fun readSticks(event: MotionEvent): Sticks
	{
		val device = event.device
		return if(isRaw(device))
			Sticks(
				event.getAxisValue(MotionEvent.AXIS_X),
				event.getAxisValue(MotionEvent.AXIS_Y),
				event.getAxisValue(MotionEvent.AXIS_Z),
				event.getAxisValue(MotionEvent.AXIS_RZ),
				normalizedTrigger(device, event, MotionEvent.AXIS_RX),
				normalizedTrigger(device, event, MotionEvent.AXIS_RY),
				event.getAxisValue(MotionEvent.AXIS_HAT_X),
				event.getAxisValue(MotionEvent.AXIS_HAT_Y))
		else
		{
			val l2 = maxOf(
				normalizedTrigger(device, event, MotionEvent.AXIS_LTRIGGER),
				normalizedTrigger(device, event, MotionEvent.AXIS_BRAKE))
			val r2 = maxOf(
				normalizedTrigger(device, event, MotionEvent.AXIS_RTRIGGER),
				normalizedTrigger(device, event, MotionEvent.AXIS_GAS))
			Sticks(
				event.getAxisValue(MotionEvent.AXIS_X),
				event.getAxisValue(MotionEvent.AXIS_Y),
				event.getAxisValue(MotionEvent.AXIS_Z),
				event.getAxisValue(MotionEvent.AXIS_RZ),
				l2, r2,
				event.getAxisValue(MotionEvent.AXIS_HAT_X),
				event.getAxisValue(MotionEvent.AXIS_HAT_Y))
		}
	}

	private fun normalizedTrigger(device: InputDevice?, event: MotionEvent, axis: Int): Float
	{
		val range = device?.getMotionRange(axis, event.source) ?: return 0f
		val value = event.getAxisValue(axis)
		val span = range.max - range.min
		if(span <= 0f)
			return 0f
		return ((value - range.min) / span).coerceIn(0f, 1f)
	}

	/**
	 * Key code that the Android UI should see for a logical gamepad button, or null to leave it alone.
	 * Cross confirms, Circle goes back, Triangle opens the context menu.
	 */
	private fun uiKeyCode(logical: Int): Int? = when(logical)
	{
		KeyEvent.KEYCODE_BUTTON_A -> if(swapCrossMoon) KeyEvent.KEYCODE_BACK else KeyEvent.KEYCODE_DPAD_CENTER
		KeyEvent.KEYCODE_BUTTON_B -> if(swapCrossMoon) KeyEvent.KEYCODE_DPAD_CENTER else KeyEvent.KEYCODE_BACK
		KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_MENU
		KeyEvent.KEYCODE_BUTTON_START -> KeyEvent.KEYCODE_MENU
		else -> null
	}

	/** Translated copy of a gamepad key event for UI navigation, or null if nothing to translate. */
	fun translateForUi(event: KeyEvent): KeyEvent?
	{
		if(!isGamepadSource(event.source))
			return null
		val target = uiKeyCode(logicalKeyCode(event)) ?: return null
		if(target == event.keyCode)
			return null
		return KeyEvent(
			event.downTime, event.eventTime, event.action, target,
			event.repeatCount, event.metaState, event.deviceId, event.scanCode,
			event.flags, event.source)
	}

	fun logicalKeyName(keyCode: Int): String = when(keyCode)
	{
		KeyEvent.KEYCODE_BUTTON_A -> "Cross ✕"
		KeyEvent.KEYCODE_BUTTON_B -> "Circle ○"
		KeyEvent.KEYCODE_BUTTON_X -> "Square □"
		KeyEvent.KEYCODE_BUTTON_Y -> "Triangle △"
		KeyEvent.KEYCODE_BUTTON_L1 -> "L1"
		KeyEvent.KEYCODE_BUTTON_R1 -> "R1"
		KeyEvent.KEYCODE_BUTTON_L2 -> "L2"
		KeyEvent.KEYCODE_BUTTON_R2 -> "R2"
		KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"
		KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
		KeyEvent.KEYCODE_BUTTON_SELECT -> "Create/Share"
		KeyEvent.KEYCODE_BUTTON_START -> "Options"
		KeyEvent.KEYCODE_BUTTON_MODE -> "PS"
		KEYCODE_TOUCHPAD -> "Touchpad"
		else -> KeyEvent.keyCodeToString(keyCode)
	}
}

/** Make Cross/Circle/Triangle of a gamepad work inside a dialog (dialogs have their own window). */
fun <T: Dialog> T.withGamepadNav(): T
{
	setOnKeyListener { dialog, _, event ->
		val translated = Gamepad.translateForUi(event) ?: return@setOnKeyListener false
		(dialog as Dialog).dispatchKeyEvent(translated)
	}
	return this
}
