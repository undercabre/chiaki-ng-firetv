// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.Window
import android.widget.Toast
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.metallic.chiaki.R
import com.metallic.chiaki.common.GamepadNavActivity
import com.metallic.chiaki.common.ext.RevealActivity
import com.metallic.chiaki.common.importSettingsFromBytes
import com.metallic.chiaki.databinding.ActivityRegistBinding
import com.metallic.chiaki.lib.RegistInfo
import com.metallic.chiaki.lib.Target
import com.metallic.chiaki.phonelink.PhoneLinkPanel
import com.metallic.chiaki.phonelink.PhoneLinkServer
import java.lang.IllegalArgumentException

class RegistActivity: GamepadNavActivity(), RevealActivity
{
	companion object
	{
		const val EXTRA_HOST = "regist_host"
		const val EXTRA_BROADCAST = "regist_broadcast"
		const val EXTRA_ASSIGN_MANUAL_HOST_ID = "assign_manual_host_id"
		const val EXTRA_IS_PS5 = "regist_is_ps5"

		private const val PIN_LENGTH = 8

		private const val REQUEST_REGIST = 1

		/** The PSN Account ID is the same for every console, remember it so it is typed only once. */
		private const val PREF_LAST_PSN_ID = "fire_tv_last_psn_id"
	}

	private lateinit var viewModel: RegistViewModel
	private lateinit var binding: ActivityRegistBinding

	override val revealWindow: Window get() = window
	override val revealIntent: Intent get() = intent
	override val revealRootLayout: View get() = binding.rootLayout

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		binding = ActivityRegistBinding.inflate(layoutInflater)
		setContentView(binding.root)
		handleReveal()

		viewModel = ViewModelProvider(this).get(RegistViewModel::class.java)
		if(savedInstanceState == null && intent.hasExtra(EXTRA_IS_PS5))
			viewModel.ps4Version.value = if(intent.getBooleanExtra(EXTRA_IS_PS5, true))
				RegistViewModel.ConsoleVersion.PS5
			else
				RegistViewModel.ConsoleVersion.PS4_GE_8

		binding.hostEditText.setText(intent.getStringExtra(EXTRA_HOST) ?: "255.255.255.255")
		binding.broadcastCheckBox.isChecked = intent.getBooleanExtra(EXTRA_BROADCAST, true)
		if(savedInstanceState == null)
			binding.psnIdEditText.setText(PreferenceManager.getDefaultSharedPreferences(this).getString(PREF_LAST_PSN_ID, ""))

		binding.registButton.setOnClickListener { doRegist() }

		binding.ps4VersionRadioGroup.check(radioIdFor(viewModel.ps4Version.value ?: RegistViewModel.ConsoleVersion.PS5))

		binding.ps4VersionRadioGroup.setOnCheckedChangeListener { _, checkedId ->
			viewModel.ps4Version.value = when(checkedId)
			{
				R.id.ps5RadioButton -> RegistViewModel.ConsoleVersion.PS5
				R.id.ps4VersionGE8RadioButton -> RegistViewModel.ConsoleVersion.PS4_GE_8
				R.id.ps4VersionGE7RadioButton -> RegistViewModel.ConsoleVersion.PS4_GE_7
				R.id.ps4VersionLT7RadioButton -> RegistViewModel.ConsoleVersion.PS4_LT_7
				else -> RegistViewModel.ConsoleVersion.PS5
			}
		}

		viewModel.ps4Version.observe(this, Observer {
			binding.psnAccountIdHelpTextView.visibility = if(it == RegistViewModel.ConsoleVersion.PS4_LT_7) View.GONE else View.VISIBLE
			binding.psnIdTextInputLayout.hint = getString(when(it!!)
			{
				RegistViewModel.ConsoleVersion.PS4_LT_7 -> R.string.hint_regist_psn_online_id
				else -> R.string.hint_regist_psn_account_id
			})
			binding.pinHelpBeforeTextView.setText(if(it.isPS5) R.string.regist_pin_instructions_ps5_before else R.string.regist_pin_instructions_ps4_before)
			binding.pinHelpNavigationTextView.setText(if(it.isPS5) R.string.regist_pin_instructions_ps5_navigation else R.string.regist_pin_instructions_ps4_navigation)
		})

		PhoneLinkPanel(this, binding.phoneLinkQrImageView, binding.phoneLinkUrlTextView, object: PhoneLinkServer.Listener {
			override fun onRegistData(data: PhoneLinkServer.RegistData) = applyPhoneData(data)
			override fun onSettingsJson(json: ByteArray) = importSettingsFromBytes(this@RegistActivity, json, lifecycleScope)
		})

		// Start on the PIN when the account ID is already known, it is the only thing left to enter.
		if(binding.psnIdEditText.text.isNullOrEmpty())
			binding.ps5RadioButton.requestFocus()
		else
			binding.pinPicker.requestFocus()
	}

	private fun radioIdFor(version: RegistViewModel.ConsoleVersion) = when(version)
	{
		RegistViewModel.ConsoleVersion.PS5 -> R.id.ps5RadioButton
		RegistViewModel.ConsoleVersion.PS4_GE_8 -> R.id.ps4VersionGE8RadioButton
		RegistViewModel.ConsoleVersion.PS4_GE_7 -> R.id.ps4VersionGE7RadioButton
		RegistViewModel.ConsoleVersion.PS4_LT_7 -> R.id.ps4VersionLT7RadioButton
	}

	private fun applyPhoneData(data: PhoneLinkServer.RegistData)
	{
		when(data.console)
		{
			"ps5" -> RegistViewModel.ConsoleVersion.PS5
			"ps4_ge_8" -> RegistViewModel.ConsoleVersion.PS4_GE_8
			"ps4_ge_7" -> RegistViewModel.ConsoleVersion.PS4_GE_7
			"ps4_lt_7" -> RegistViewModel.ConsoleVersion.PS4_LT_7
			else -> null
		}?.let { binding.ps4VersionRadioGroup.check(radioIdFor(it)) }
		data.accountId?.let { binding.psnIdEditText.setText(it) }
		data.pin?.let { binding.pinPicker.setPin(it) }
		data.host?.let {
			binding.hostEditText.setText(it)
			binding.broadcastCheckBox.isChecked = false
		}
		binding.phoneLinkStatusTextView.setText(R.string.phone_link_received)
		Toast.makeText(this, R.string.phone_link_received, Toast.LENGTH_LONG).show()
		binding.registButton.requestFocus()
	}

	private fun doRegist()
	{
		val ps4Version = viewModel.ps4Version.value ?: RegistViewModel.ConsoleVersion.PS5

		val host = binding.hostEditText.text.toString().trim()
		val hostValid = host.isNotEmpty()
		val broadcast = binding.broadcastCheckBox.isChecked

		val psnId = binding.psnIdEditText.text.toString().trim()
		val psnOnlineId: String? = if(ps4Version == RegistViewModel.ConsoleVersion.PS4_LT_7) psnId else null
		val psnAccountId: ByteArray? =
			if(ps4Version != RegistViewModel.ConsoleVersion.PS4_LT_7)
				try { Base64.decode(psnId, Base64.DEFAULT) } catch(e: IllegalArgumentException) { null }
			else
				null
		val psnIdValid = when(ps4Version)
		{
			RegistViewModel.ConsoleVersion.PS4_LT_7 -> psnOnlineId?.isNotEmpty() ?: false
			else -> psnAccountId != null && psnAccountId.size == RegistInfo.ACCOUNT_ID_SIZE
		}

		val pin = binding.pinPicker.pin
		val pinValid = pin.length == PIN_LENGTH && pin != "0".repeat(PIN_LENGTH)

		binding.hostEditText.error = if(!hostValid) getString(R.string.entered_host_invalid) else null
		binding.psnIdEditText.error =
			if(!psnIdValid)
				getString(when(ps4Version)
				{
					RegistViewModel.ConsoleVersion.PS4_LT_7 -> R.string.regist_psn_online_id_invalid
					else -> R.string.regist_psn_account_id_invalid
				})
			else
				null
		binding.pinErrorTextView.visibility = if(pinValid) View.GONE else View.VISIBLE
		binding.pinErrorTextView.text = getString(R.string.regist_pin_invalid, PIN_LENGTH)

		if(!hostValid || !psnIdValid || !pinValid)
		{
			when
			{
				!psnIdValid -> binding.psnIdEditText.requestFocus()
				!pinValid -> binding.pinPicker.requestFocus()
				else -> binding.hostEditText.requestFocus()
			}
			return
		}

		PreferenceManager.getDefaultSharedPreferences(this).edit().putString(PREF_LAST_PSN_ID, psnId).apply()

		val target = when(ps4Version)
		{
			RegistViewModel.ConsoleVersion.PS5 -> Target.PS5_1
			RegistViewModel.ConsoleVersion.PS4_GE_8 -> Target.PS4_10
			RegistViewModel.ConsoleVersion.PS4_GE_7 -> Target.PS4_9
			RegistViewModel.ConsoleVersion.PS4_LT_7 -> Target.PS4_8
		}

		val registInfo = RegistInfo(target, host, broadcast, psnOnlineId, psnAccountId, pin.toInt())

		Intent(this, RegistExecuteActivity::class.java).also {
			it.putExtra(RegistExecuteActivity.EXTRA_REGIST_INFO, registInfo)
			if(intent.hasExtra(EXTRA_ASSIGN_MANUAL_HOST_ID))
				it.putExtra(RegistExecuteActivity.EXTRA_ASSIGN_MANUAL_HOST_ID, intent.getLongExtra(EXTRA_ASSIGN_MANUAL_HOST_ID, 0L))
			@Suppress("DEPRECATION")
			startActivityForResult(it, REQUEST_REGIST)
		}
	}

	@Deprecated("Deprecated in Java")
	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
	{
		@Suppress("DEPRECATION")
		super.onActivityResult(requestCode, resultCode, data)
		if(requestCode == REQUEST_REGIST && resultCode == RESULT_OK)
			finish()
	}
}
