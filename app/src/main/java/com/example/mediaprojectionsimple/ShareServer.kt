package com.example.mediaprojectionsimple

import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors

/** Session-only, passwordless-by-design capability URL with a 128-bit random access token.
 * Served exclusively on the active Wi-Fi IPv4 address, never through a remote relay.
 */
class ShareServer(address: String, private val frame: () -> ByteArray?) {
    private val secret = ByteArray(16).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
    private val socket = ServerSocket(0, 8, InetAddress.getByName(address))
    private val clients = Executors.newFixedThreadPool(3)
    @Volatile private var closed = false
    val url: String = "http://$address:${socket.localPort}/?key=$secret"

    fun start() {
        Thread({
            while (!closed) {
                try {
                    val visitor = socket.accept()
                    clients.execute { handle(visitor) }
                } catch (_: Exception) {
                    if (!closed) continue
                }
            }
        }, "mirror-http").apply { isDaemon = true; start() }
    }

    private fun handle(client: Socket) {
        client.use { connection ->
            try {
                connection.soTimeout = 4000
                val lines = connection.getInputStream().bufferedReader(Charsets.US_ASCII)
                val request = lines.readLine()?.take(2048) ?: return
                for (i in 0 until 40) {
                    val header = lines.readLine() ?: break
                    if (header.isEmpty()) break
                }
                val parts = request.split(' ')
                if (parts.size < 2 || parts[0] != "GET") {
                    send(connection.getOutputStream(), 405, "text/plain", "Only GET".toByteArray())
                    return
                }
                val route = parts[1]
                val query = route.substringAfter('?', "")
                val presentedKey = query.split('&').firstOrNull { it.startsWith("key=") }?.removePrefix("key=")
                if (presentedKey != secret) {
                    send(connection.getOutputStream(), 403, "text/plain", "Forbidden".toByteArray())
                    return
                }
                if (route.substringBefore('?') == "/frame") {
                    val bytes = frame()
                    if (bytes == null) send(connection.getOutputStream(), 503, "text/plain", "Waiting for screen".toByteArray())
                    else send(connection.getOutputStream(), 200, "image/jpeg", bytes)
                } else if (route.substringBefore('?') == "/") {
                    send(connection.getOutputStream(), 200, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))
                } else {
                    send(connection.getOutputStream(), 404, "text/plain", "Not found".toByteArray())
                }
            } catch (_: Exception) { /* a remote browser closed the socket */ }
        }
    }

    private fun send(out: OutputStream, code: Int, mime: String, bytes: ByteArray) {
        val label = when (code) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Service Unavailable" }
        out.write(("HTTP/1.1 $code $label\r\nContent-Type: $mime\r\n" +
            "Content-Length: ${bytes.size}\r\nCache-Control: no-store, no-cache, must-revalidate\r\n" +
            "X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n" +
            "Content-Security-Policy: default-src 'none'; img-src 'self' data:; script-src 'unsafe-inline'; style-src 'unsafe-inline'\r\n" +
            "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    fun stop() {
        closed = true
        try { socket.close() } catch (_: Exception) {}
        clients.shutdownNow()
    }

    private val html = """
        <!doctype html><html lang="pl"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Live Screen Mirror</title><style>
        body{margin:0;background:#0d121d;color:#e5fff8;font:16px system-ui;text-align:center}
        header{padding:16px;font-weight:700}small{color:#90adbf}
        img{display:block;max-width:100vw;max-height:85vh;object-fit:contain;margin:auto;background:#000}
        </style></head><body><header>◉ LIVE SCREEN MIRROR<br><small>Podgląd tylko • lokalne Wi-Fi</small></header>
        <img id="screen" alt="Oczekiwanie na ekran"><p id="status">Łączenie…</p>
        <script>
        const key=new URLSearchParams(location.search).get('key');
        const img=document.getElementById('screen'); const status=document.getElementById('status');
        function tick(){const temp=new Image();
          temp.onload=()=>{img.src=temp.src;status.textContent='Transmisja aktywna';setTimeout(tick,500)};
          temp.onerror=()=>{status.textContent='Oczekiwanie na ekran lub połączenie';setTimeout(tick,1400)};
          temp.src='/frame?key='+encodeURIComponent(key)+'&v='+Date.now();}
        tick();
        </script></body></html>
    """.trimIndent()
}
