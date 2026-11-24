// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.content.pm.PackageManager
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity

/**
 * Base activity for every menu screen: lets a DualSense drive the UI
 * (Cross = select, Circle = back, Triangle/Options = context menu), whatever its HID mapping.
 */
open class GamepadNavActivity: AppCompatActivity()
{
	override fun onResume()
	{
		super.onResume()
		Gamepad.loadSettings(this)
	}

	override fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		val translated = Gamepad.translateForUi(event)
		return super.dispatchKeyEvent(translated ?: event)
	}
}

val android.content.Context.hasTouchscreen: Boolean
	get() = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

val android.content.Context.isTv: Boolean
	get() = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
			packageManager.hasSystemFeature("amazon.hardware.fire_tv") ||
			!hasTouchscreen
