// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.*
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.controllertest.ControllerTestActivity
import com.metallic.chiaki.databinding.ActivityMainBinding
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.DiscoveryHost
import com.metallic.chiaki.manualconsole.EditManualConsoleActivity
import com.metallic.chiaki.regist.RegistActivity
import com.metallic.chiaki.settings.SettingsActivity
import com.metallic.chiaki.stream.StreamActivity

/**
 * Fire TV home screen. Navigation: D-pad / left stick to move, Cross to select,
 * Triangle (or Options, or ≡ on the remote) for the console context menu.
 */
class MainActivity : GamepadNavActivity()
{
	private lateinit var viewModel: MainViewModel
	private lateinit var binding: ActivityMainBinding

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		binding = ActivityMainBinding.inflate(layoutInflater)
		setContentView(binding.root)

		binding.registerButton.setOnClickListener { showRegistration() }
		binding.addManualButton.setOnClickListener { addManualConsole() }
		binding.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
		binding.controllerTestButton.setOnClickListener { startActivity(Intent(this, ControllerTestActivity::class.java)) }
		binding.discoverButton.setOnClickListener {
			viewModel.discoveryManager.active = !(viewModel.discoveryActive.value ?: false)
		}

		viewModel = ViewModelProvider(this, viewModelFactory { MainViewModel(getDatabase(this), Preferences(this)) })
			.get(MainViewModel::class.java)

		val recyclerViewAdapter = DisplayHostRecyclerViewAdapter(this::hostTriggered, this::showHostMenu)
		binding.hostsRecyclerView.adapter = recyclerViewAdapter
		binding.hostsRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
		viewModel.displayHosts.observe(this, Observer {
			val hadHosts = recyclerViewAdapter.itemCount > 0
			recyclerViewAdapter.hosts = it
			updateEmptyInfo()
			if(!hadHosts && it.isNotEmpty())
				focusFirstHost()
		})

		viewModel.discoveryActive.observe(this, Observer { active ->
			binding.discoverButton.setText(if(active) R.string.action_discover_on else R.string.action_discover_off)
			binding.discoverButton.setIconResource(if(active) R.drawable.ic_discover_on else R.drawable.ic_discover_off)
			updateEmptyInfo()
		})

		binding.registerButton.requestFocus()
	}

	/** Put the focus on the first console card so Cross connects right away. */
	private fun focusFirstHost()
	{
		binding.hostsRecyclerView.post {
			val focused = currentFocus
			if(focused == null || focused == binding.registerButton)
				binding.hostsRecyclerView.layoutManager?.findViewByPosition(0)?.requestFocus()
		}
	}

	private fun updateEmptyInfo()
	{
		if(viewModel.displayHosts.value?.isEmpty() ?: true)
		{
			binding.emptyInfoLayout.visibility = View.VISIBLE
			val discoveryActive = viewModel.discoveryActive.value ?: false
			binding.emptyInfoImageView.setImageResource(if(discoveryActive) R.drawable.ic_discover_on else R.drawable.ic_discover_off)
			binding.emptyInfoTextView.setText(if(discoveryActive) R.string.display_hosts_empty_discovery_on_info else R.string.display_hosts_empty_discovery_off_info)
		}
		else
			binding.emptyInfoLayout.visibility = View.GONE
	}

	override fun onStart()
	{
		super.onStart()
		viewModel.discoveryManager.resume()
	}

	override fun onStop()
	{
		super.onStop()
		viewModel.discoveryManager.pause()
	}

	private fun addManualConsole()
	{
		startActivity(Intent(this, EditManualConsoleActivity::class.java))
	}

	private fun showRegistration()
	{
		startActivity(Intent(this, RegistActivity::class.java))
	}

	private fun showHostMenu(host: DisplayHost, canWakeup: Boolean, canEditDelete: Boolean)
	{
		val actions = mutableListOf<Pair<Int, () -> Unit>>()
		if(canWakeup)
			actions.add(R.string.action_wakeup to { wakeupHost(host) })
		if(canEditDelete)
		{
			actions.add(R.string.action_edit to { editHost(host) })
			actions.add(R.string.action_delete to { deleteHost(host) })
		}
		if(actions.isEmpty())
			return
		MaterialAlertDialogBuilder(this)
			.setTitle(host.name)
			.setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which -> actions[which].second() }
			.create()
			.withGamepadNav()
			.show()
	}

	private fun hostTriggered(host: DisplayHost)
	{
		val registeredHost = host.registeredHost
		if(registeredHost != null)
		{
			fun connect() {
				val connectInfo = ConnectInfo(host.isPS5, host.host, registeredHost.rpRegistKey, registeredHost.rpKey, Preferences(this).videoProfile)
				Intent(this, StreamActivity::class.java).let {
					it.putExtra(StreamActivity.EXTRA_CONNECT_INFO, connectInfo)
					startActivity(it)
				}
			}

			if(host is DiscoveredDisplayHost && host.discoveredHost.state == DiscoveryHost.State.STANDBY)
			{
				MaterialAlertDialogBuilder(this)
					.setMessage(R.string.alert_message_standby_wakeup)
					.setPositiveButton(R.string.action_wakeup) { _, _ ->
						wakeupHost(host)
					}
					.setNeutralButton(R.string.action_connect_immediately) { _, _ ->
						connect()
					}
					.setNegativeButton(R.string.action_connect_cancel_connect) { _, _ -> }
					.create()
					.withGamepadNav()
					.show()
			}
			else
				connect()
		}
		else
		{
			Intent(this, RegistActivity::class.java).let {
				it.putExtra(RegistActivity.EXTRA_HOST, host.host)
				it.putExtra(RegistActivity.EXTRA_BROADCAST, false)
				it.putExtra(RegistActivity.EXTRA_IS_PS5, host.isPS5)
				if(host is ManualDisplayHost)
					it.putExtra(RegistActivity.EXTRA_ASSIGN_MANUAL_HOST_ID, host.manualHost.id)
				startActivity(it)
			}
		}
	}

	private fun wakeupHost(host: DisplayHost)
	{
		val registeredHost = host.registeredHost ?: return
		viewModel.discoveryManager.sendWakeup(host.host, registeredHost.rpRegistKey, registeredHost.target.isPS5)
	}

	private fun editHost(host: DisplayHost)
	{
		if(host !is ManualDisplayHost)
			return
		Intent(this, EditManualConsoleActivity::class.java).also {
			it.putExtra(EditManualConsoleActivity.EXTRA_MANUAL_HOST_ID, host.manualHost.id)
			startActivity(it)
		}
	}

	private fun deleteHost(host: DisplayHost)
	{
		if(host !is ManualDisplayHost)
			return
		MaterialAlertDialogBuilder(this)
			.setMessage(getString(R.string.alert_message_delete_manual_host, host.manualHost.host))
			.setPositiveButton(R.string.action_delete) { _, _ ->
				viewModel.deleteManualHost(host.manualHost)
			}
			.setNegativeButton(R.string.action_keep) { _, _ -> }
			.create()
			.withGamepadNav()
			.show()
	}
}
