// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.controllertest

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Gamepad
import com.metallic.chiaki.databinding.ActivityControllerTestBinding

/**
 * Shows what the app understands from the controller, so a wrong mapping on Fire OS
 * can be spotted and fixed in Settings → Controller mapping.
 * Every gamepad button is consumed here; leave with the remote's Back or by holding Circle.
 *
 * With [EXTRA_LEARN_TOUCHPAD] the next key pressed is remembered as the touchpad click
 * (Fire OS without DualSense driver may deliver the click as an unknown key).
 */
class ControllerTestActivity: AppCompatActivity()
{
	companion object
	{
		const val EXTRA_LEARN_TOUCHPAD = "learn_touchpad"
	}

	private lateinit var binding: ActivityControllerTestBinding
	private val pressed = linkedSetOf<Int>()
	private val handler = Handler(Looper.getMainLooper())
	private val exitRunnable = Runnable { finish() }
	private var learnTouchpad = false

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		binding = ActivityControllerTestBinding.inflate(layoutInflater)
		setContentView(binding.root)
		learnTouchpad = intent.getBooleanExtra(EXTRA_LEARN_TOUCHPAD, false)
		if(learnTouchpad)
			binding.deviceTextView.setText(R.string.controller_test_learn_touchpad)
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
			binding.captureView.setOnCapturedPointerListener { _, event -> onTouchpadEvent(event) }
	}

	override fun onResume()
	{
		super.onResume()
		Gamepad.loadSettings(this)
	}

	override fun onPause()
	{
		super.onPause()
		handler.removeCallbacks(exitRunnable)
	}

	override fun onWindowFocusChanged(hasFocus: Boolean)
	{
		super.onWindowFocusChanged(hasFocus)
		if(hasFocus && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
		{
			binding.captureView.requestFocus()
			binding.captureView.requestPointerCapture()
		}
	}

	/** Touchpad seen as a pointer device (captured): show fingers and click. */
	private fun onTouchpadEvent(event: MotionEvent): Boolean
	{
		val click = event.buttonState and (MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_SECONDARY) != 0
		val fingers = (0 until event.pointerCount).joinToString("  ") { i ->
			String.format("(%.0f, %.0f)", event.getX(i), event.getY(i))
		}
		val sourceName = when
		{
			event.isFromSource(InputDevice.SOURCE_TOUCHPAD) -> "touchpad"
			event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE) -> "mouse (relative)"
			else -> "pointer 0x" + Integer.toHexString(event.source)
		}
		binding.touchpadTextView.text = getString(R.string.controller_test_touchpad,
			sourceName, event.device?.name ?: "?", fingers, if(click) "✓" else "—")
		if(learnTouchpad && click)
		{
			// The touchpad already works as a pointer: nothing to learn
			Toast.makeText(this, R.string.controller_test_touchpad_pointer_ok, Toast.LENGTH_LONG).show()
			finish()
		}
		return true
	}

	private fun showDevice(device: InputDevice?)
	{
		device ?: return
		if(learnTouchpad)
			return
		val raw = Gamepad.isRaw(device)
		binding.deviceTextView.text = getString(R.string.controller_test_device,
			device.name,
			String.format("%04x", device.vendorId),
			String.format("%04x", device.productId),
			getString(if(raw) R.string.controller_test_mapping_raw else R.string.controller_test_mapping_standard),
			Gamepad.mapping.value)
	}

	override fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		if(!Gamepad.isGamepadSource(event.source))
			return super.dispatchKeyEvent(event)
		showDevice(event.device)
		val logical = Gamepad.logicalKeyCode(event)

		if(learnTouchpad && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && logical != KeyEvent.KEYCODE_BUTTON_B)
		{
			Gamepad.learnTouchpadKey(this, event)
			Toast.makeText(this, getString(R.string.controller_test_touchpad_learned,
				KeyEvent.keyCodeToString(event.keyCode), event.scanCode), Toast.LENGTH_LONG).show()
			finish()
			return true
		}

		when(event.action)
		{
			KeyEvent.ACTION_DOWN -> {
				if(event.repeatCount == 0)
				{
					pressed.add(logical)
					if(logical == KeyEvent.KEYCODE_BUTTON_B)
						handler.postDelayed(exitRunnable, 2000)
				}
			}
			KeyEvent.ACTION_UP -> {
				pressed.remove(logical)
				if(logical == KeyEvent.KEYCODE_BUTTON_B)
					handler.removeCallbacks(exitRunnable)
			}
		}
		binding.buttonsTextView.text = pressed.joinToString("  ") { Gamepad.logicalKeyName(it) }
		binding.lastRawTextView.text = getString(R.string.controller_test_last_raw,
			KeyEvent.keyCodeToString(event.keyCode), event.scanCode, Gamepad.logicalKeyName(logical))
		return true
	}

	override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean
	{
		if(event.source and InputDevice.SOURCE_CLASS_JOYSTICK != InputDevice.SOURCE_CLASS_JOYSTICK)
			return super.dispatchGenericMotionEvent(event)
		showDevice(event.device)
		val s = Gamepad.readSticks(event)
		binding.axesTextView.text = String.format(
			"L  x %+.2f  y %+.2f     R  x %+.2f  y %+.2f\nL2 %.2f   R2 %.2f     D-pad x %+.0f  y %+.0f",
			s.leftX, s.leftY, s.rightX, s.rightY, s.l2, s.r2, s.hatX, s.hatY)
		return true
	}
}
