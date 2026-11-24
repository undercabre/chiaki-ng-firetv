// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.phonelink

import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.metallic.chiaki.R

/**
 * Runs a [PhoneLinkServer] while the owner is started and shows its QR code / URL.
 */
class PhoneLinkPanel(
	owner: LifecycleOwner,
	private val qrImageView: ImageView,
	private val urlTextView: TextView,
	listener: PhoneLinkServer.Listener
)
{
	private val server = PhoneLinkServer(listener)

	init
	{
		owner.lifecycle.addObserver(object: DefaultLifecycleObserver {
			override fun onStart(owner: LifecycleOwner) = start()
			override fun onStop(owner: LifecycleOwner) = server.stop()
		})
	}

	private fun start()
	{
		val url = if(server.start()) server.url() else null
		if(url == null)
		{
			qrImageView.setImageDrawable(null)
			urlTextView.setText(R.string.phone_link_unavailable)
			return
		}
		urlTextView.text = url
		qrImageView.setImageBitmap(PhoneLinkServer.qrBitmap(url))
	}
}
