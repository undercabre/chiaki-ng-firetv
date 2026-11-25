// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.BitrateDialog
import com.metallic.chiaki.common.Gamepad
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.exportAndShareAllSettings
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.common.getDatabase
import com.metallic.chiaki.common.hasTouchscreen
import com.metallic.chiaki.common.importSettingsFromUri
import com.metallic.chiaki.common.isTv
import com.metallic.chiaki.common.withGamepadNav
import com.metallic.chiaki.controllertest.ControllerTestActivity
import com.metallic.chiaki.phonelink.PhoneImportActivity

class DataStore(val preferences: Preferences): PreferenceDataStore()
{
	override fun getBoolean(key: String?, defValue: Boolean) = when(key)
	{
		preferences.logVerboseKey -> preferences.logVerbose
		preferences.swapCrossMoonKey -> preferences.swapCrossMoon
		preferences.rumbleEnabledKey -> preferences.rumbleEnabled
		preferences.motionEnabledKey -> preferences.motionEnabled
		preferences.buttonHapticEnabledKey -> preferences.buttonHapticEnabled
		else -> defValue
	}

	override fun putBoolean(key: String?, value: Boolean)
	{
		when(key)
		{
			preferences.logVerboseKey -> preferences.logVerbose = value
			preferences.swapCrossMoonKey -> preferences.swapCrossMoon = value
			preferences.rumbleEnabledKey -> preferences.rumbleEnabled = value
			preferences.motionEnabledKey -> preferences.motionEnabled = value
			preferences.buttonHapticEnabledKey -> preferences.buttonHapticEnabled = value
		}
	}

	override fun getString(key: String, defValue: String?) = when(key)
	{
		preferences.resolutionKey -> preferences.resolution.value
		preferences.fpsKey -> preferences.fps.value
		preferences.bitrateKey -> preferences.bitrate?.toString() ?: ""
		preferences.codecKey -> preferences.codec.value
		preferences.controllerMappingKey -> preferences.controllerMapping.value
		preferences.touchpadAltKey -> preferences.touchpadAlt.value
		else -> defValue
	}

	override fun putString(key: String, value: String?)
	{
		when(key)
		{
			preferences.resolutionKey ->
			{
				val resolution = Preferences.Resolution.values().firstOrNull { it.value == value } ?: return
				preferences.resolution = resolution
			}
			preferences.fpsKey ->
			{
				val fps = Preferences.FPS.values().firstOrNull { it.value == value } ?: return
				preferences.fps = fps
			}
			preferences.bitrateKey -> preferences.bitrate = value?.toIntOrNull()
			preferences.codecKey ->
			{
				val codec = Preferences.Codec.values().firstOrNull { it.value == value } ?: return
				preferences.codec = codec
			}
			preferences.controllerMappingKey -> preferences.controllerMapping = Gamepad.Mapping.fromValue(value)
			preferences.touchpadAltKey -> preferences.touchpadAlt = Gamepad.TouchpadAlt.fromValue(value)
		}
	}
}

class SettingsFragment: PreferenceFragmentCompat(), TitleFragment
{
	companion object
	{
		private const val PICK_SETTINGS_JSON_REQUEST = 1
	}

	private lateinit var preferences: Preferences

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?)
	{
		val context = context ?: return

		val viewModel = ViewModelProvider(this, viewModelFactory { SettingsViewModel(getDatabase(context), Preferences(context)) })
			.get(SettingsViewModel::class.java)

		val preferences = viewModel.preferences
		this.preferences = preferences
		preferenceManager.preferenceDataStore = DataStore(preferences)
		setPreferencesFromResource(R.xml.preferences, rootKey)

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_resolution_key))?.let {
			it.entryValues = Preferences.resolutionAll.map { res -> res.value }.toTypedArray()
			it.entries = Preferences.resolutionAll.map { res -> getString(res.title) }.toTypedArray()
		}

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_fps_key))?.let {
			it.entryValues = Preferences.fpsAll.map { fps -> fps.value }.toTypedArray()
			it.entries = Preferences.fpsAll.map { fps -> getString(fps.title) }.toTypedArray()
		}

		// Free value 2-100 Mbps, adjustable with the controller (no keyboard on Fire TV)
		val bitratePreference = preferenceScreen.findPreference<Preference>(getString(R.string.preferences_bitrate_key))
		fun updateBitrateSummary()
		{
			bitratePreference?.summary = preferences.bitrate?.let { getString(R.string.preferences_bitrate_value, it / 1000) }
				?: getString(R.string.preferences_bitrate_auto, preferences.bitrateAuto)
		}
		updateBitrateSummary()
		bitratePreference?.setOnPreferenceClickListener {
			BitrateDialog.show(context, getString(R.string.preferences_bitrate_title), preferences.bitrate, preferences.bitrateAuto) {
				preferences.bitrate = it
				updateBitrateSummary()
			}
			true
		}
		viewModel.bitrateAuto.observe(this, Observer { updateBitrateSummary() })

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_codec_key))?.let {
			it.entryValues = Preferences.codecAll.map { codec -> codec.value }.toTypedArray()
			it.entries = Preferences.codecAll.map { codec -> getString(codec.title) }.toTypedArray()
		}

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_controller_mapping_key))?.let {
			it.entryValues = Gamepad.Mapping.values().map { m -> m.value }.toTypedArray()
			it.entries = arrayOf(
				getString(R.string.preferences_controller_mapping_auto),
				getString(R.string.preferences_controller_mapping_standard),
				getString(R.string.preferences_controller_mapping_sony_raw))
			it.setOnPreferenceChangeListener { _, _ ->
				listView?.post { Gamepad.loadSettings(context) }
				true
			}
		}

		preferenceScreen.findPreference<Preference>("controller_test")?.setOnPreferenceClickListener {
			startActivity(Intent(context, ControllerTestActivity::class.java))
			true
		}

		preferenceScreen.findPreference<Preference>("firetv_profile")?.setOnPreferenceClickListener {
			MaterialAlertDialogBuilder(context)
				.setTitle(R.string.preferences_firetv_profile_title)
				.setMessage(R.string.preferences_firetv_profile_confirm)
				.setPositiveButton(R.string.action_apply) { _, _ ->
					preferences.applyFireTvProfile()
					Gamepad.loadSettings(context)
					// Rebuild the screen so every summary shows the new values
					activity?.recreate()
				}
				.setNegativeButton(android.R.string.cancel) { _, _ -> }
				.create()
				.withGamepadNav()
				.show()
			true
		}

		// Touchpad: learn the key its click produces on this Fire TV, or use another button instead
		touchpadLearnPreference = preferenceScreen.findPreference("touchpad_learn")
		touchpadLearnPreference?.setOnPreferenceClickListener {
			val learn = {
				startActivity(Intent(context, ControllerTestActivity::class.java)
					.putExtra(ControllerTestActivity.EXTRA_LEARN_TOUCHPAD, true))
			}
			if(!Gamepad.hasLearnedTouchpad)
				learn()
			else
				MaterialAlertDialogBuilder(context)
					.setTitle(R.string.preferences_touchpad_learn_title)
					.setItems(arrayOf(getString(R.string.touchpad_learn_again), getString(R.string.touchpad_learn_forget))) { _, which ->
						if(which == 0)
							learn()
						else
						{
							Gamepad.learnTouchpadKey(context, null)
							updateTouchpadLearnSummary()
						}
					}
					.create()
					.withGamepadNav()
					.show()
			true
		}

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_touchpad_alt_key))?.let {
			it.entryValues = Gamepad.TouchpadAlt.values().map { a -> a.value }.toTypedArray()
			it.entries = arrayOf(
				getString(R.string.preferences_touchpad_alt_none),
				getString(R.string.preferences_touchpad_alt_create),
				getString(R.string.preferences_touchpad_alt_l3r3))
			it.setOnPreferenceChangeListener { _, _ ->
				listView?.post { Gamepad.loadSettings(context) }
				true
			}
		}

		// Options that only make sense on a phone or tablet
		if(!context.hasTouchscreen)
		{
			preferenceScreen.findPreference<Preference>(getString(R.string.preferences_button_haptic_enabled_key))?.isVisible = false
			preferenceScreen.findPreference<Preference>(getString(R.string.preferences_motion_enabled_key))?.isVisible = false
		}
		preferenceScreen.findPreference<Preference>(getString(R.string.preferences_rumble_enabled_key))?.setSummary(
			if(context.isTv) R.string.preferences_rumble_enabled_summary_tv else R.string.preferences_rumble_enabled_summary)

		val registeredHostsPreference = preferenceScreen.findPreference<Preference>("registered_hosts")
		viewModel.registeredHostsCount.observe(this, Observer {
			registeredHostsPreference?.summary = getString(R.string.preferences_registered_hosts_summary, it)
		})

		preferenceScreen.findPreference<Preference>(getString(R.string.preferences_export_settings_key))?.setOnPreferenceClickListener { exportSettings(); true }
		preferenceScreen.findPreference<Preference>(getString(R.string.preferences_import_settings_key))?.let {
			it.isVisible = !context.isTv // no file picker on Fire TV, use the phone instead
			it.setOnPreferenceClickListener { importSettings(); true }
		}
		preferenceScreen.findPreference<Preference>("import_settings_phone")?.setOnPreferenceClickListener {
			startActivity(Intent(context, PhoneImportActivity::class.java))
			true
		}
	}

	/** List preferences as plain dialogs that understand the DualSense (Cross/Circle). */
	@Suppress("DEPRECATION")
	override fun onDisplayPreferenceDialog(preference: Preference)
	{
		if(preference !is ListPreference)
		{
			super.onDisplayPreferenceDialog(preference)
			return
		}
		val context = context ?: return
		val values = preference.entryValues ?: return
		val checked = values.indexOfFirst { it.toString() == preference.value }
		MaterialAlertDialogBuilder(context)
			.setTitle(preference.title)
			.setSingleChoiceItems(preference.entries, checked) { dialog, which ->
				val value = values[which].toString()
				if(preference.callChangeListener(value))
					preference.value = value
				dialog.dismiss()
			}
			.setNegativeButton(android.R.string.cancel) { _, _ -> }
			.create()
			.withGamepadNav()
			.show()
	}

	private var touchpadLearnPreference: Preference? = null

	private fun updateTouchpadLearnSummary()
	{
		touchpadLearnPreference?.summary =
			if(Gamepad.hasLearnedTouchpad)
				getString(R.string.preferences_touchpad_learn_summary_done,
					android.view.KeyEvent.keyCodeToString(Gamepad.touchpadLearnedKeyCode), Gamepad.touchpadLearnedScanCode)
			else
				getString(R.string.preferences_touchpad_learn_summary)
	}

	override fun onResume()
	{
		super.onResume()
		context?.let { Gamepad.loadSettings(it) }
		updateTouchpadLearnSummary()
	}

	override fun getTitle(resources: Resources): String = resources.getString(R.string.title_settings)

	private fun exportSettings()
	{
		val activity = activity ?: return
		try
		{
			exportAndShareAllSettings(activity, lifecycleScope)
		}
		catch(e: ActivityNotFoundException)
		{
			Toast.makeText(activity, R.string.no_app_for_action, Toast.LENGTH_LONG).show()
		}
	}

	private fun importSettings()
	{
		val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
			addCategory(Intent.CATEGORY_OPENABLE)
			type = "application/json"
		}
		try
		{
			@Suppress("DEPRECATION")
			startActivityForResult(intent, PICK_SETTINGS_JSON_REQUEST)
		}
		catch(e: ActivityNotFoundException)
		{
			startActivity(Intent(context, PhoneImportActivity::class.java))
		}
	}

	@Deprecated("Deprecated in Java")
	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
	{
		if(requestCode == PICK_SETTINGS_JSON_REQUEST && resultCode == Activity.RESULT_OK)
		{
			val activity = activity ?: return
			data?.data?.also {
				importSettingsFromUri(activity, it, lifecycleScope)
			}
		}
	}
}
