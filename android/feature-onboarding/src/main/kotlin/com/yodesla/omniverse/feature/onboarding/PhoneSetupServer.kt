package com.yodesla.omniverse.feature.onboarding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * "Set up from your phone": while the Add-source screen is open, the TV serves a one-page form
 * on the local network. The user types their provider login on a phone instead of a TV remote.
 *
 * Safety:
 *  - Runs only while the screen is visible; [stop] closes the socket.
 *  - A fresh 4-digit [code] shown on the TV must be entered; 5 wrong codes shut the server down.
 *  - Nothing is logged or stored here: the fields go straight into the onboarding form, which
 *    validates them like typed input. No values are echoed back in responses.
 */
class PhoneSetupServer(
    private val onSubmit: (PhoneSetupFields) -> Unit,
    private val port: Int = DEFAULT_PORT,
    random: SecureRandom = SecureRandom(),
) {
    val code: String = String.format(java.util.Locale.ROOT, "%04d", random.nextInt(10_000))

    @Volatile private var socket: ServerSocket? = null
    private var job: Job? = null
    /** Atomic: parallel requests must not squeeze in more guesses than [MAX_WRONG_CODES]. */
    private val wrongCodes = java.util.concurrent.atomic.AtomicInteger(0)

    /** Starts listening; returns the bound port, or null if the port is taken. */
    fun start(scope: CoroutineScope): Int? {
        val server = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }
        } catch (e: java.io.IOException) {
            return null
        }
        socket = server
        job = scope.launch(Dispatchers.IO) {
            while (isActive && !server.isClosed) {
                val client = try { server.accept() } catch (e: java.io.IOException) { break }
                launch { client.use { handle(it) } }
            }
        }
        return server.localPort
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        job?.cancel()
    }

    private fun handle(client: Socket) {
        client.soTimeout = 10_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val requestLine = try { reader.readLine() } catch (e: SocketTimeoutException) { null } ?: return
        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).equals("Content-Length", ignoreCase = true)) {
                contentLength = line.substring(colon + 1).trim().toIntOrNull()?.coerceIn(0, MAX_BODY) ?: 0
            }
        }
        val method = requestLine.substringBefore(' ')
        val path = requestLine.substringAfter(' ').substringBefore(' ').substringBefore('?')
        val out = client.getOutputStream()
        fun respond(status: String, html: String) {
            val body = html.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.US_ASCII)); out.write(body); out.flush()
        }
        when {
            method == "GET" && (path == "/" || path == "/index.html") -> respond("200 OK", page(FORM))
            method == "POST" && path == "/submit" -> {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                val fields = parseForm(String(buf, 0, read))
                if (wrongCodes.get() >= MAX_WRONG_CODES) {
                    respond("403 Forbidden", page(message("Locked", "Too many wrong codes. Reopen the setup screen on your TV.", back = false)))
                    stop()
                    return
                }
                if (fields["code"]?.trim() != code) {
                    val n = wrongCodes.incrementAndGet()
                    respond("403 Forbidden", page(message("That code didn't match", "Check the 4-digit code on your TV and try again.", back = true)))
                    if (n >= MAX_WRONG_CODES) stop()
                    return
                }
                val f = PhoneSetupFields(
                    server = fields["server"].orEmpty().trim(),
                    username = fields["username"].orEmpty().trim(),
                    password = fields["password"].orEmpty(),
                    m3uUrl = fields["m3u"].orEmpty().trim(),
                    name = fields["name"].orEmpty().trim(),
                )
                onSubmit(f)
                respond("200 OK", page(message("Sent to your TV", "Look at the TV: it is checking your login now. You can close this page.", back = false)))
            }
            else -> respond("404 Not Found", page(message("Not found", "Open the address shown on your TV.", back = true)))
        }
    }

    companion object {
        const val DEFAULT_PORT = 8123
        private const val MAX_BODY = 8 * 1024
        private const val MAX_WRONG_CODES = 5

        fun parseForm(body: String): Map<String, String> = body.split('&').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) null
            else runCatching {
                URLDecoder.decode(pair.substring(0, eq), "UTF-8") to URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            }.getOrNull()
        }.toMap()

        /** This device's LAN IPv4 (what a phone on the same Wi-Fi can reach), if any. */
        fun lanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()

        private fun message(title: String, text: String, back: Boolean) =
            "<h1>$title</h1><p>$text</p>" + if (back) "<p><a href=\"/\">Back</a></p>" else ""

        private val FORM = """
            <h1>Add your source</h1>
            <p class="sub">Type your provider login here instead of using the TV remote.</p>
            <form method="post" action="/submit" autocomplete="off">
              <label>Code on your TV<input name="code" inputmode="numeric" maxlength="4" required></label>
              <label>Server URL<input name="server" placeholder="http://example.com:8080" autocapitalize="none"></label>
              <label>Username<input name="username" autocapitalize="none" autocorrect="off"></label>
              <label>Password<input name="password" type="password"></label>
              <details><summary>Using an M3U link instead?</summary>
                <label>M3U playlist URL<input name="m3u" autocapitalize="none"></label></details>
              <label>Name (optional)<input name="name"></label>
              <button type="submit">Send to TV</button>
            </form>
        """.trimIndent()

        private fun page(content: String) = """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Omniverse setup</title>
            <style>
              body{margin:0;background:#08080A;color:#F4EFE6;font:16px/1.5 system-ui,-apple-system,sans-serif}
              main{max-width:440px;margin:0 auto;padding:32px 20px}
              h1{font-family:Georgia,serif;font-weight:400;font-size:34px;margin:0 0 6px}
              .sub,p{color:#B9B2A6}
              label{display:block;margin:16px 0 0;font-size:13px;letter-spacing:.08em;text-transform:uppercase;color:#D9B97A}
              input{display:block;width:100%;box-sizing:border-box;margin-top:6px;padding:14px;border-radius:12px;
                border:1px solid #2A2930;background:#141418;color:#F4EFE6;font-size:17px}
              input:focus{outline:2px solid #D9B97A;border-color:transparent}
              details{margin-top:16px;color:#B9B2A6}
              button{margin-top:24px;width:100%;padding:16px;border:0;border-radius:28px;background:#D9B97A;color:#0B0B0E;
                font-size:17px;font-weight:700}
              a{color:#D9B97A}
            </style></head><body><main>$content</main></body></html>
        """.trimIndent()
    }
}

data class PhoneSetupFields(val server: String, val username: String, val password: String, val m3uUrl: String, val name: String)
