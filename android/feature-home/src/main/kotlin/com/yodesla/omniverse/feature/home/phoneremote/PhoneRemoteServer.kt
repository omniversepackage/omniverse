package com.yodesla.omniverse.feature.home.phoneremote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom
import com.yodesla.omniverse.core.model.NowPlaying

/**
 * "Phone remote": while Phone remote is On (or the Settings screen is open) the TV serves a single
 * self-contained page on the LAN. A phone on the same Wi-Fi searches the catalog, taps a result to
 * play it on the TV, and sends D-pad / play-pause keys. Mirrors [com.yodesla.omniverse.feature.onboarding.PhoneSetupServer]'s
 * safety model.
 *
 * Safety:
 *  - Binds only to the LAN address (never 0.0.0.0), so it is not reachable from the internet.
 *  - A per-session [token] (in the URL) is required on EVERY request; a wrong token is 403.
     *  - Data endpoints (search / action / now) also require the 6-digit [code] shown on the TV; 5
     *    wrong codes shut the server down.
 *  - Nothing is logged or echoed back. No provider credentials are ever served or accepted.
 */
class PhoneRemoteServer(
    private val token: String,
    private val code: String,
    private val onAction: (PhoneRemoteAction) -> Unit,
    private val onSearch: suspend (String) -> List<RemoteResult>,
    private val onConnected: () -> Unit,
    /** Task 91: the app-level Now Playing snapshot (null when nothing plays). */
    private val onNowPlaying: () -> NowPlaying? = { null },
    private val port: Int = DEFAULT_PORT,
    random: SecureRandom = SecureRandom(),
) {
    @Volatile private var socket: ServerSocket? = null
    private var job: Job? = null
    private val wrongCodes = java.util.concurrent.atomic.AtomicInteger(0)

    /** Starts listening on [bindHost] (a LAN address, or "127.0.0.1" in tests). Returns the bound
     *  port, or null if the host is unusable or the port is taken. */
    fun start(scope: CoroutineScope, bindHost: String): Int? {
        val addr = runCatching { InetAddress.getByName(bindHost) }.getOrNull() ?: return null
        val server = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(addr, port)) }
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

    private suspend fun handle(client: Socket) {
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
        val target = requestLine.substringAfter(' ').substringBefore(' ')
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
        var body = ""
        if (method == "POST" && contentLength > 0) {
            val buf = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(buf, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            body = String(buf, 0, read)
        }
        val params = parseQuery(query) + parseQuery(body)
        val out = client.getOutputStream()
        fun respond(status: String, contentType: String, payload: String) {
            val bytes = payload.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 $status\r\nContent-Type: $contentType; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.US_ASCII)); out.write(bytes); out.flush()
        }
        // Token is required on every request, including the page itself.
        if (params["k"] != token) {
            respond("403 Forbidden", "text/html", page(message("Not paired", "Open the address shown on your TV.", back = false)))
            return
        }
        when {
            method == "GET" && (path == "/" || path == "/index.html") -> {
                onConnected()
                respond("200 OK", "text/html", PAGE)
            }
            method == "GET" && path == "/search" -> {
                if (!checkCode(params, ::respond)) return
                onConnected()
                val results = runCatching { onSearch(params["q"].orEmpty()) }.getOrDefault(emptyList())
                respond("200 OK", "application/json", jsonResults(results))
            }
            method == "POST" && path == "/action" -> {
                if (!checkCode(params, ::respond)) return
                onConnected()
                val action = parseAction(params["action"].orEmpty())
                if (action == null) { respond("400 Bad Request", "application/json", "{\"error\":\"bad action\"}"); return }
                onAction(action)
                respond("200 OK", "application/json", "{\"ok\":true}")
            }
            method == "GET" && path == "/now" -> {
                if (!checkCode(params, ::respond)) return
                respond("200 OK", "application/json", jsonNowPlaying(onNowPlaying()))
            }
            else -> respond("404 Not Found", "text/html", page(message("Not found", "Open the address shown on your TV.", back = true)))
        }
    }

    /** 6-digit code gate for data endpoints, with the same 5-strike shutdown as the setup server. */
    private fun checkCode(params: Map<String, String>, respond: (String, String, String) -> Unit): Boolean {
        if (wrongCodes.get() >= MAX_WRONG_CODES) {
            respond("403 Forbidden", "text/html", page(message("Locked", "Too many wrong codes. Turn Phone remote off and on again on your TV.", back = false)))
            stop()
            return false
        }
        if (params["code"]?.trim() != code) {
            val n = wrongCodes.incrementAndGet()
            respond("403 Forbidden", "application/json", "{\"error\":\"wrong code\"}")
            if (n >= MAX_WRONG_CODES) stop()
            return false
        }
        return true
    }

    companion object {
        const val DEFAULT_PORT = 8124
        private const val MAX_BODY = 8 * 1024
        private const val MAX_WRONG_CODES = 5
        private const val MAX_SEEK_MS = 24L * 60 * 60 * 1000

        fun newToken(random: SecureRandom = SecureRandom()): String {
            val b = ByteArray(6); random.nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }
        fun newCode(random: SecureRandom = SecureRandom()): String =
            String.format(java.util.Locale.ROOT, "%06d", random.nextInt(1_000_000))

        fun parseQuery(s: String): Map<String, String> = if (s.isEmpty()) emptyMap() else s.split('&').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) null
            else runCatching {
                URLDecoder.decode(pair.substring(0, eq), "UTF-8") to URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            }.getOrNull()
        }.toMap()

        /** Maps a phone action string to a [PhoneRemoteAction]. Returns null for anything unknown. */
        fun parseAction(raw: String): PhoneRemoteAction? {
            val p = raw.split(':')
            return when (p.firstOrNull()) {
                "key" -> when (p.getOrNull(1)) {
                    "up" -> PhoneRemoteAction.Key(PhoneRemoteKey.Up)
                    "down" -> PhoneRemoteAction.Key(PhoneRemoteKey.Down)
                    "left" -> PhoneRemoteAction.Key(PhoneRemoteKey.Left)
                    "right" -> PhoneRemoteAction.Key(PhoneRemoteKey.Right)
                    "ok" -> PhoneRemoteAction.Key(PhoneRemoteKey.Ok)
                    "back" -> PhoneRemoteAction.Key(PhoneRemoteKey.Back)
                    "playpause" -> PhoneRemoteAction.Key(PhoneRemoteKey.PlayPause)
                    else -> null
                }
                "play" -> when (p.getOrNull(1)) {
                    "channel" -> if (p.size >= 5) PhoneRemoteAction.PlayChannel(
                        com.yodesla.omniverse.core.model.ContentKey(
                            com.yodesla.omniverse.core.model.SourceId(p[2]),
                            com.yodesla.omniverse.core.model.ContentKind.LIVE,
                            com.yodesla.omniverse.core.model.RemoteId(p[3]),
                        ),
                        com.yodesla.omniverse.core.model.RemoteId(p[4]),
                    ) else null
                    "movie" -> if (p.size >= 4) PhoneRemoteAction.PlayMovie(
                        com.yodesla.omniverse.core.model.ContentKey(
                            com.yodesla.omniverse.core.model.SourceId(p[2]),
                            com.yodesla.omniverse.core.model.ContentKind.VOD,
                            com.yodesla.omniverse.core.model.RemoteId(p[3]),
                        )
                    ) else null
                    else -> null
                }
                "open" -> if (p.getOrNull(1) == "show" && p.size >= 4) PhoneRemoteAction.OpenShow(
                    com.yodesla.omniverse.core.model.ContentKey(
                        com.yodesla.omniverse.core.model.SourceId(p[2]),
                        com.yodesla.omniverse.core.model.ContentKind.SERIES,
                        com.yodesla.omniverse.core.model.RemoteId(p[3]),
                    )
                ) else null
                // Task 91 Now Playing controls. Engine track ids contain ':' ("1:2"), so the id is
                // everything after the command.
                "seek" -> p.getOrNull(1)?.toLongOrNull()?.takeIf { it >= 0 && it <= MAX_SEEK_MS }
                    ?.let { PhoneRemoteAction.Seek(it) }
                "skip" -> p.getOrNull(1)?.toLongOrNull()?.takeIf { it != 0L && it >= -MAX_SEEK_MS && it <= MAX_SEEK_MS }
                    ?.let { PhoneRemoteAction.Skip(it) }
                "audio" -> p.drop(1).joinToString(":").takeIf { it.isNotEmpty() }?.let { PhoneRemoteAction.SelectAudio(it) }
                "subs" -> when (p.drop(1).joinToString(":")) {
                    "" -> null
                    "off" -> PhoneRemoteAction.SelectSubtitle(null)
                    else -> PhoneRemoteAction.SelectSubtitle(p.drop(1).joinToString(":"))
                }
                else -> null
            }
        }

        /** This device's LAN IPv4 (what a phone on the same Wi-Fi can reach), if any. */
        fun lanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()

        private fun esc(s: String): String = buildString {
            for (c in s) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }

        /** Task 91: the Now Playing snapshot as JSON; `{"nowPlaying":null}` when nothing plays. */
        internal fun jsonNowPlaying(np: NowPlaying?): String {
            if (np == null) return "{\"nowPlaying\":null}"
            fun tracks(list: List<com.yodesla.omniverse.core.model.NowPlayingTrack>): String =
                list.joinToString(",", "[", "]") { "{\"id\":\"${esc(it.id)}\",\"label\":\"${esc(it.label)}\"}" }
            fun str(s: String?): String = s?.let { "\"${esc(it)}\"" } ?: "null"
            return "{\"nowPlaying\":{\"title\":\"${esc(np.title)}\",\"poster\":${str(np.posterUrl)}," +
                "\"kind\":\"${esc(np.kind)}\",\"positionMs\":${np.positionMs},\"durationMs\":${np.durationMs}," +
                "\"isPlaying\":${np.isPlaying},\"isLive\":${np.isLive},\"channel\":${str(np.channelName)}," +
                "\"audio\":${tracks(np.audioTracks)},\"audioSelected\":${str(np.selectedAudioId)}," +
                "\"subs\":${tracks(np.subtitleTracks)},\"subsSelected\":${str(np.selectedSubtitleId)}}}"
        }

        private fun jsonResults(results: List<RemoteResult>): String {
            val items = results.joinToString(",") { r ->
                "{\"kind\":\"${esc(r.kind)}\",\"title\":\"${esc(r.title)}\",\"subtitle\":${r.subtitle?.let { "\"${esc(it)}\"" } ?: "null"}," +
                    "\"poster\":${r.posterUrl?.let { "\"${esc(it)}\"" } ?: "null"}," +
                    "\"action\":\"${esc(r.action)}\",\"sourceId\":\"${esc(r.sourceId)}\",\"remoteId\":\"${esc(r.remoteId)}\"," +
                    "\"categoryId\":${r.categoryId?.let { "\"${esc(it)}\"" } ?: "null"}}"
            }
            return "{\"results\":[$items]}"
        }

        private fun message(title: String, text: String, back: Boolean) =
            "<h1>$title</h1><p>$text</p>" + if (back) "<p><a href=\"/\">Back</a></p>" else ""

        private val PAGE = """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Omniverse remote</title>
            <style>
              body{margin:0;background:#08080A;color:#F4EFE6;font:16px/1.5 system-ui,-apple-system,sans-serif}
              main{max-width:520px;margin:0 auto;padding:24px 18px 60px}
              h1{font-family:Georgia,serif;font-weight:400;font-size:28px;margin:0 0 4px}
              .sub{color:#B9B2A6;margin:0 0 16px}
              input{width:100%;box-sizing:border-box;padding:12px;border-radius:12px;border:1px solid #2A2930;
                background:#141418;color:#F4EFE6;font-size:16px}
              .row{display:flex;gap:10px;margin:12px 0}
              .keys{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin:18px 0}
              .keys button,.act{padding:14px;border:0;border-radius:14px;background:#22222A;color:#F4EFE6;font-size:16px}
              .act{background:#D9B97A;color:#0B0B0E;font-weight:700}
              .res{display:flex;gap:12px;align-items:center;padding:10px 0;border-bottom:1px solid #1E1E24}
              .res img{width:44px;height:66px;object-fit:cover;border-radius:6px;background:#141418}
              .res b{display:block}.res span{color:#B9B2A6;font-size:14px}
              #status{color:#D9B97A;min-height:20px;margin-top:10px}
              #now{border:1px solid #2A2930;border-radius:14px;background:#141418;padding:14px;margin:0 0 16px}
              #now.hidden{display:none}
              .np{display:flex;gap:12px;align-items:center}
              .np img{width:44px;height:66px;object-fit:cover;border-radius:6px;background:#08080A}
              .np b{display:block}.np span{color:#B9B2A6;font-size:14px}
              #npSlider{flex:1;accent-color:#D9B97A}
              #now .row{align-items:center}
              #now button{padding:10px 14px;border:0;border-radius:12px;background:#22222A;color:#F4EFE6;font-size:15px}
              #now select{flex:1;padding:10px;border-radius:12px;border:1px solid #2A2930;background:#08080A;
                color:#F4EFE6;font-size:15px}
              #npTimes{display:flex;justify-content:space-between;color:#B9B2A6;font-size:14px;margin-top:6px}
            </style></head><body><main>
              <h1>Omniverse remote</h1>
              <p class="sub">Search and control your TV from this phone.</p>
              <div id="now" class="hidden">
                <div class="np">
                  <img id="npPoster" alt="">
                  <div><b id="npTitle"></b><span id="npSub"></span></div>
                </div>
                <div class="row" id="npSeekRow">
                  <button id="npBack">-10s</button>
                  <input type="range" id="npSlider" min="0" max="0" value="0">
                  <button id="npFwd">+10s</button>
                </div>
                <div id="npTimes"><span id="npElapsed"></span><span id="npRemaining"></span></div>
                <div class="row">
                  <button id="npPlay" class="act">Pause</button>
                  <select id="npAudio"></select>
                  <select id="npSubs"></select>
                </div>
              </div>
              <div class="row"><input id="code" inputmode="numeric" maxlength="6" placeholder="6-digit code from your TV"></div>
              <div class="row"><input id="q" placeholder="Search channels, movies, shows" autocapitalize="none"></div>
              <div class="keys">
                <span></span><button data-k="up">Up</button><span></span>
                <button data-k="left">Left</button><button data-k="ok">OK</button><button data-k="right">Right</button>
                <button data-k="down">Down</button><button data-k="back">Back</button><button data-k="playpause">Play/Pause</button>
              </div>
              <div id="status"></div>
              <div id="results"></div>
              <script>
                var token = new URLSearchParams(location.search).get('k') || '';
                var status = document.getElementById('status');
                function code(){ return document.getElementById('code').value.trim(); }
                function key(k){ post('action', 'action=' + encodeURIComponent('key:' + k)); }
                document.querySelectorAll('.keys button').forEach(function(b){ b.onclick = function(){ key(b.dataset.k); }; });
                function post(path, body){
                  return fetch('/' + path + '?k=' + encodeURIComponent(token), {
                    method:'POST', headers:{'Content-Type':'application/x-www-form-urlencoded'},
                    body: body + '&code=' + encodeURIComponent(code())
                  }).then(function(r){ if(r.status===403) status.textContent='Check the code on your TV.'; return r; });
                }
                function search(){
                  var q = document.getElementById('q').value.trim();
                  if(!q){ return; }
                  status.textContent='Searching…';
                  fetch('/search?k=' + encodeURIComponent(token) + '&q=' + encodeURIComponent(q) + '&code=' + encodeURIComponent(code()))
                    .then(function(r){ return r.json(); })
                    .then(function(d){
                      var box = document.getElementById('results'); box.innerHTML='';
                      (d.results||[]).forEach(function(it){
                        var row = document.createElement('div'); row.className='res';
                        var a = it.action==='channel' ? ('play:channel:'+it.sourceId+':'+it.remoteId+':'+it.categoryId)
                          : it.action==='movie' ? ('play:movie:'+it.sourceId+':'+it.remoteId)
                          : ('open:show:'+it.sourceId+':'+it.remoteId);
                        row.innerHTML = '<img src="'+(it.poster||'')+'">' +
                          '<div><b>'+esc(it.title)+'</b><span>'+esc(it.subtitle||it.kind)+'</span></div>';
                        row.onclick = function(){ post('action','action='+encodeURIComponent(a)); status.textContent='Playing on your TV: '+it.title; };
                        box.appendChild(row);
                      });
                      status.textContent = (d.results||[]).length ? '' : 'Nothing found.';
                    })
                    .catch(function(){ status.textContent='Search failed.'; });
                }
                document.getElementById('q').addEventListener('keydown', function(e){ if(e.key==='Enter') search(); });
                function esc(s){ return String(s==null?'':s).replace(/[&<>"]/g, function(c){ return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]; }); }
                // Task 91: Now Playing card. Polls /now every 2 s while the page is visible and a
                // code is entered; a 403 pauses polling so polling can never trip the 5-strike lockout.
                var pausedUntil = 0, dragging = false, lastTracks = {};
                function fmt(ms){
                  var s = Math.max(0, Math.round(ms/1000)), h = Math.floor(s/3600), m = Math.floor((s%3600)/60);
                  var ss = (s%60<10?'0':'')+(s%60);
                  return h ? (h+':'+(m<10?'0':'')+m+':'+ss) : (m+':'+ss);
                }
                function fillSelect(sel, tracks, selected, offLabel){
                  var sig = JSON.stringify(tracks)+'|'+(selected||'');
                  if(sig === lastTracks[sel.id]) return;
                  lastTracks[sel.id] = sig;
                  sel.innerHTML = '';
                  if(offLabel){ var o = document.createElement('option'); o.value='off'; o.textContent=offLabel; sel.appendChild(o); }
                  (tracks||[]).forEach(function(t){
                    var o = document.createElement('option'); o.value = t.id; o.textContent = t.label; sel.appendChild(o);
                  });
                  sel.value = selected || (offLabel ? 'off' : ((tracks||[])[0]||{}).id || '');
                }
                function renderNow(np){
                  var card = document.getElementById('now');
                  if(!np){ card.classList.add('hidden'); return; }
                  card.classList.remove('hidden');
                  document.getElementById('npTitle').textContent = np.title || '';
                  document.getElementById('npPoster').src = np.poster || '';
                  document.getElementById('npPoster').style.display = np.poster ? '' : 'none';
                  document.getElementById('npPlay').textContent = np.isPlaying ? 'Pause' : 'Play';
                  var seekRow = document.getElementById('npSeekRow'), times = document.getElementById('npTimes');
                  if(np.isLive){
                    seekRow.style.display = 'none'; times.style.display = 'none';
                    document.getElementById('npSub').textContent = (np.channel || 'Live TV') + (np.title ? ' — ' + np.title : '');
                  } else {
                    seekRow.style.display = ''; times.style.display = '';
                    document.getElementById('npSub').textContent = np.kind;
                    var slider = document.getElementById('npSlider');
                    slider.max = np.durationMs || 0;
                    if(!dragging) slider.value = np.positionMs || 0;
                    document.getElementById('npElapsed').textContent = fmt(np.positionMs||0);
                    document.getElementById('npRemaining').textContent = np.durationMs ? '-'+fmt(np.durationMs-(np.positionMs||0)) : '';
                  }
                  fillSelect(document.getElementById('npAudio'), np.audio, np.audioSelected, null);
                  fillSelect(document.getElementById('npSubs'), np.subs, np.subsSelected, 'Subtitles off');
                }
                function pollNow(){
                  if(document.hidden || !code() || Date.now() < pausedUntil) return;
                  fetch('/now?k=' + encodeURIComponent(token) + '&code=' + encodeURIComponent(code()))
                    .then(function(r){
                      if(r.status===403){ pausedUntil = Date.now() + 10000; status.textContent='Check the code on your TV.'; return null; }
                      return r.json();
                    })
                    .then(function(d){ if(d) renderNow(d.nowPlaying); })
                    .catch(function(){});
                }
                setInterval(pollNow, 2000);
                var slider = document.getElementById('npSlider');
                slider.addEventListener('input', function(){
                  dragging = true;
                  document.getElementById('npElapsed').textContent = fmt(+slider.value);
                  var max = +slider.max; if(max) document.getElementById('npRemaining').textContent = '-'+fmt(max-(+slider.value));
                });
                slider.addEventListener('change', function(){
                  dragging = false;
                  post('action', 'action=' + encodeURIComponent('seek:' + slider.value));
                });
                document.getElementById('npBack').onclick = function(){ post('action','action=' + encodeURIComponent('skip:-10000')); };
                document.getElementById('npFwd').onclick = function(){ post('action','action=' + encodeURIComponent('skip:+10000')); };
                document.getElementById('npPlay').onclick = function(){ key('playpause'); };
                document.getElementById('npAudio').onchange = function(){ post('action','action=' + encodeURIComponent('audio:' + this.value)); };
                document.getElementById('npSubs').onchange = function(){ post('action','action=' + encodeURIComponent('subs:' + this.value)); };
              </script>
            </main></body></html>
        """.trimIndent()

        private fun page(content: String) =
            "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                "<title>Omniverse remote</title><style>body{margin:0;background:#08080A;color:#F4EFE6;" +
                "font:16px/1.5 system-ui,sans-serif}main{max-width:520px;margin:0 auto;padding:24px 18px}" +
                "h1{font-family:Georgia,serif;font-weight:400}a{color:#D9B97A}</style></head><body><main>" +
                content + "</main></body></html>"
    }
}
