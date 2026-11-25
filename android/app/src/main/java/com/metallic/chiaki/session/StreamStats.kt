// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.os.SystemClock

/** One second of stream statistics, computed from the native cumulative counters. */
data class StreamStats(
	val fps: Double,
	val droppedPerSecond: Double,
	val lostPerSecond: Double,
	val decodeMsAvg: Double,
	val decodeMsMax: Double,
	val rttMs: Double,
	val bitrateMbps: Double,
	val packetLossPercent: Double,
	val lowLatencyDecoder: Boolean)

/** Turns the cumulative native counters into per-second values. */
class StreamStatsCalculator
{
	private var last: DoubleArray? = null
	private var lastTime = 0L

	fun update(raw: DoubleArray?): StreamStats?
	{
		raw ?: return null
		if(raw.size < 10)
			return null
		val now = SystemClock.uptimeMillis()
		val prev = last
		val seconds = (now - lastTime) / 1000.0
		last = raw
		lastTime = now
		if(prev == null || seconds <= 0.0)
			return null
		fun delta(i: Int) = (raw[i] - prev[i]).coerceAtLeast(0.0)
		val samples = delta(4)
		return StreamStats(
			fps = delta(0) / seconds,
			droppedPerSecond = delta(1) / seconds,
			lostPerSecond = delta(2) / seconds,
			decodeMsAvg = if(samples > 0) delta(3) / samples / 1000.0 else 0.0,
			decodeMsMax = raw[5] / 1000.0,
			rttMs = raw[6],
			bitrateMbps = raw[7],
			packetLossPercent = raw[8] * 100.0,
			lowLatencyDecoder = raw[9] > 0.5)
	}
}
