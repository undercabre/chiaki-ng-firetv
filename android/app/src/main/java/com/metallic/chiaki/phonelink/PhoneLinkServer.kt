// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.phonelink

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Tiny LAN-only HTTP server so the user can type with the phone instead of the TV remote.
 *
 * The TV shows a QR code with http://<tv-ip>:<port>/<token>. The page lets the phone send
 * the PSN Account ID + registration PIN (+ optional console address), or upload a settings
 * JSON exported from the Chiaki Android app. The random token in the path keeps other
 * devices on the network from posting by accident. The server only runs while the screen
 * that started it is open.
 */
class PhoneLinkServer(private val listener: Listener)
{
	companion object
	{
		private const val TAG = "PhoneLinkServer"
		private const val PREFERRED_PORT = 8765
		private const val MAX_BODY = 1 shl 20

		private const val ACCOUNT_ID_HELP_URL = "https://chiaki-ng.github.io/chiaki-ng/setup/configuration/#registering-your-playstation"

		fun localIpv4Address(): String?
		{
			val candidates = try
			{
				NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
					.filter { it.isUp && !it.isLoopback && !it.isVirtual }
					.flatMap { iface -> iface.inetAddresses.toList().map { iface.name to it } }
					.filter { (_, addr) -> addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress }
			}
			catch(e: SocketException) { emptyList() }
			// Prefer ethernet / wifi interfaces
			return (candidates.firstOrNull { it.first.startsWith("eth") }
				?: candidates.firstOrNull { it.first.startsWith("wlan") }
				?: candidates.firstOrNull())?.second?.hostAddress
		}

		fun qrBitmap(text: String, size: Int = 512): Bitmap
		{
			val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
			val pixels = IntArray(size * size)
			for(y in 0 until size)
				for(x in 0 until size)
					pixels[y * size + x] = if(matrix[x, y]) Color.BLACK else Color.WHITE
			return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
		}
	}

	data class RegistData(val host: String?, val console: String?, val accountId: String?, val pin: String?)

	interface Listener
	{
		fun onRegistData(data: RegistData) {}
		fun onSettingsJson(json: ByteArray) {}
	}

	val token: String = SecureRandom().let { rnd ->
		val chars = "abcdefghijkmnpqrstuvwxyz23456789"
		(1..6).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
	}

	@Volatile private var serverSocket: ServerSocket? = null
	private val mainHandler = Handler(Looper.getMainLooper())

	val port: Int get() = serverSocket?.localPort ?: 0

	/** URL to show on screen, or null if the server is not running / no LAN address. */
	fun url(): String?
	{
		if(serverSocket == null)
			return null
		val ip = localIpv4Address() ?: return null
		return "http://$ip:$port/$token"
	}

	fun start(): Boolean
	{
		if(serverSocket != null)
			return true
		val socket = try { ServerSocket(PREFERRED_PORT) }
			catch(e: IOException) {
				try { ServerSocket(0) } catch(e2: IOException) { Log.e(TAG, "Failed to open server socket", e2); return false }
			}
		serverSocket = socket
		thread(name = "PhoneLinkServer", isDaemon = true) {
			while(!socket.isClosed)
			{
				val client = try { socket.accept() } catch(e: IOException) { break }
				thread(name = "PhoneLinkClient", isDaemon = true) { handleClient(client) }
			}
		}
		return true
	}

	fun stop()
	{
		try { serverSocket?.close() } catch(e: IOException) {}
		serverSocket = null
	}

	private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

	private fun handleClient(client: Socket)
	{
		client.use { socket ->
			try
			{
				socket.soTimeout = 10000
				val request = readRequest(BufferedInputStream(socket.getInputStream())) ?: return
				val (status, html) = route(request)
				val bytes = html.toByteArray(Charsets.UTF_8)
				val header = "HTTP/1.1 $status\r\n" +
						"Content-Type: text/html; charset=utf-8\r\n" +
						"Content-Length: ${bytes.size}\r\n" +
						"Cache-Control: no-store\r\n" +
						"Connection: close\r\n\r\n"
				socket.getOutputStream().apply {
					write(header.toByteArray(Charsets.ISO_8859_1))
					write(bytes)
					flush()
				}
			}
			catch(e: IOException)
			{
				Log.w(TAG, "Client error", e)
			}
		}
	}

	private fun readLine(input: InputStream): String?
	{
		val out = ByteArrayOutputStream()
		while(true)
		{
			val b = input.read()
			if(b < 0)
				return if(out.size() == 0) null else out.toString("ISO-8859-1")
			if(b == '\n'.code)
				break
			if(b != '\r'.code)
				out.write(b)
			if(out.size() > 8192)
				throw IOException("Header line too long")
		}
		return out.toString("ISO-8859-1")
	}

	private fun readRequest(input: InputStream): Request?
	{
		val requestLine = readLine(input) ?: return null
		val parts = requestLine.split(" ")
		if(parts.size < 2)
			return null
		val headers = mutableMapOf<String, String>()
		while(true)
		{
			val line = readLine(input) ?: break
			if(line.isEmpty())
				break
			val idx = line.indexOf(':')
			if(idx > 0)
				headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
			if(headers.size > 64)
				throw IOException("Too many headers")
		}
		val length = headers["content-length"]?.toIntOrNull() ?: 0
		if(length < 0 || length > MAX_BODY)
			throw IOException("Body too large")
		val body = ByteArray(length)
		var read = 0
		while(read < length)
		{
			val r = input.read(body, read, length - read)
			if(r < 0)
				break
			read += r
		}
		return Request(parts[0].uppercase(), parts[1], headers, body)
	}

	private fun route(request: Request): Pair<String, String>
	{
		val path = request.path.substringBefore('?').trimEnd('/')
		val base = "/$token"
		return when
		{
			request.method == "GET" && path == base -> "200 OK" to formPage()
			request.method == "POST" && path == "$base/regist" -> {
				val form = parseForm(String(request.body, Charsets.UTF_8))
				val data = RegistData(
					host = form["host"]?.trim()?.takeIf { it.isNotEmpty() },
					console = form["console"],
					accountId = form["account_id"]?.trim()?.takeIf { it.isNotEmpty() },
					pin = form["pin"]?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() })
				mainHandler.post { listener.onRegistData(data) }
				"200 OK" to donePage("Sent to the TV. Check the TV screen and press Register. / Enviado a la TV: mira la pantalla y pulsa Registrar.")
			}
			request.method == "POST" && path == "$base/settings" -> {
				if(request.body.isEmpty())
					"400 Bad Request" to donePage("Empty file / Archivo vacío")
				else
				{
					val json = request.body
					mainHandler.post { listener.onSettingsJson(json) }
					"200 OK" to donePage("Settings sent to the TV. Confirm the import there. / Ajustes enviados: confirma la importación en la TV.")
				}
			}
			else -> "404 Not Found" to donePage("Not found. Scan the QR code on the TV again. / No encontrado: vuelve a escanear el QR de la TV.")
		}
	}

	private fun parseForm(body: String): Map<String, String> =
		body.split('&').mapNotNull { pair ->
			val idx = pair.indexOf('=')
			if(idx <= 0)
				null
			else
				try {
					URLDecoder.decode(pair.substring(0, idx), "UTF-8") to URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
				} catch(e: IllegalArgumentException) { null }
		}.toMap()

	private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

	private val style = """
		<meta name="viewport" content="width=device-width, initial-scale=1">
		<style>
		body{font-family:system-ui,sans-serif;background:#000128;color:#fff;margin:0;padding:16px;max-width:560px;margin:auto}
		h1{font-size:22px} h2{font-size:18px;margin-top:28px}
		label{display:block;margin:14px 0 4px;font-weight:600}
		input,select{width:100%;box-sizing:border-box;padding:12px;font-size:17px;border-radius:10px;border:1px solid #556;background:#162d50;color:#fff}
		button{margin-top:18px;width:100%;padding:14px;font-size:17px;font-weight:700;border:0;border-radius:24px;background:#ffaaee;color:#000}
		.hint{opacity:.75;font-size:14px;margin-top:4px} a{color:#ffaaee}
		.card{background:#0c1a3a;border-radius:14px;padding:4px 16px 18px;margin-top:16px}
		</style>""".trimIndent()

	private fun donePage(message: String) = """
		<!doctype html><html><head><meta charset="utf-8">$style<title>Chiaki FireTV</title></head>
		<body><h1>Chiaki FireTV</h1><p>${escape(message)}</p><p><a href="/$token">← Back / Volver</a></p></body></html>
		""".trimIndent()

	private fun formPage() = """
		<!doctype html><html><head><meta charset="utf-8">$style<title>Chiaki FireTV</title></head>
		<body>
		<h1>Chiaki FireTV · Phone link</h1>
		<p class="hint">Use your phone as keyboard for the Fire TV. / Usa el móvil como teclado de la Fire TV.</p>

		<div class="card">
		<h2>1 · Register a console / Registrar una consola</h2>
		<form method="post" action="/$token/regist">
			<label>Console / Consola</label>
			<select name="console">
				<option value="ps5">PS5</option>
				<option value="ps4_ge_8">PS4 ≥ 8.0</option>
				<option value="ps4_ge_7">PS4 ≥ 7.0, &lt; 8</option>
				<option value="ps4_lt_7">PS4 &lt; 7.0</option>
			</select>
			<label>PSN Account ID (base64)</label>
			<input name="account_id" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="AbCdEfGhIjk=">
			<div class="hint">How to get it / Cómo obtenerlo: <a href="$ACCOUNT_ID_HELP_URL" target="_blank">chiaki-ng docs</a>.
			For PS4 &lt; 7.0 type the PSN online ID (user name). / Para PS4 &lt; 7.0 escribe el nombre de usuario PSN.</div>
			<label>PIN (8 digits / 8 dígitos)</label>
			<input name="pin" inputmode="numeric" pattern="[0-9]{8}" maxlength="8" autocomplete="off">
			<div class="hint">PS5: Settings → System → Remote Play → Link Device. PS4: Settings → Remote Play Connection Settings → Add Device.</div>
			<label>Console address (optional) / IP de la consola (opcional)</label>
			<input name="host" inputmode="decimal" autocomplete="off" placeholder="192.168.1.50">
			<button type="submit">Send to TV / Enviar a la TV</button>
		</form>
		</div>

		<div class="card">
		<h2>2 · Import settings / Importar ajustes</h2>
		<p class="hint">JSON exported from Chiaki / chiaki-ng on Android (Settings → Export Settings). It contains your Remote Play keys: only use it on your own network.
		/ JSON exportado desde Chiaki en Android (Ajustes → Exportar). Contiene tus claves de Remote Play: úsalo solo en tu red.</p>
		<input type="file" id="file" accept="application/json,.json">
		<button type="button" onclick="sendFile()">Send file / Enviar archivo</button>
		<p id="status" class="hint"></p>
		</div>

		<script>
		function sendFile(){
			var f=document.getElementById('file').files[0]; var s=document.getElementById('status');
			if(!f){s.textContent='Choose a file first / Elige un archivo';return;}
			f.text().then(function(t){return fetch('/$token/settings',{method:'POST',headers:{'Content-Type':'application/json'},body:t});})
			 .then(function(r){s.textContent=r.ok?'Sent. Confirm on the TV. / Enviado: confirma en la TV.':'Error '+r.status;})
			 .catch(function(e){s.textContent='Error: '+e;});
		}
		</script>
		</body></html>
		""".trimIndent()
}
