// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.controllertest

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Gamepad
import com.metallic.chiaki.databinding.ActivityControllerTestBinding

/**
 * Shows what the app understands from the controller, so a wrong mapping on Fire OS
 * can be spotted and fixed in Settings → Controller mapping.
 * Every gamepad button is consumed here; leave with the remote's Back or by holding Circle.
 */
class ControllerTestActivity: AppCompatActivity()
{
	private lateinit var binding: ActivityControllerTestBinding
	private val pressed = linkedSetOf<Int>()
	private val handler = Handler(Looper.getMainLooper())
	private val exitRunnable = Runnable { finish() }

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		binding = ActivityControllerTestBinding.inflate(layoutInflater)
		setContentView(binding.root)
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

	private fun showDevice(device: InputDevice?)
	{
		device ?: return
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
