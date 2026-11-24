// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.phonelink

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.metallic.chiaki.common.GamepadNavActivity
import com.metallic.chiaki.common.importSettingsFromBytes
import com.metallic.chiaki.databinding.ActivityPhoneImportBinding

/** Import a settings JSON sent from the phone (Fire TV has no file picker). */
class PhoneImportActivity: GamepadNavActivity()
{
	private lateinit var binding: ActivityPhoneImportBinding

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		binding = ActivityPhoneImportBinding.inflate(layoutInflater)
		setContentView(binding.root)
		binding.doneButton.setOnClickListener { finish() }
		binding.doneButton.requestFocus()
		PhoneLinkPanel(this, binding.qrImageView, binding.urlTextView, object: PhoneLinkServer.Listener {
			override fun onSettingsJson(json: ByteArray) = importSettingsFromBytes(this@PhoneImportActivity, json, lifecycleScope)
		})
	}
}
