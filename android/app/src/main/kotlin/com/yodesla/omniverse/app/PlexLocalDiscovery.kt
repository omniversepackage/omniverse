package com.yodesla.omniverse.app

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Plex GDM discovers the current LAN address without relying on plex.tv's public-network guess. */
internal suspend fun discoverLocalPlexConnections(machineId: String): List<String> = withContext(Dispatchers.IO) {
    DatagramSocket().use { socket ->
        socket.broadcast = true
        val request = "M-SEARCH * HTTP/1.0\r\n\r\n".toByteArray(Charsets.UTF_8)
        socket.send(DatagramPacket(request, request.size, InetAddress.getByName("255.255.255.255"), 32414))
        val deadline = System.nanoTime() + 1_500_000_000L
        while (System.nanoTime() < deadline) {
            socket.soTimeout = ((deadline - System.nanoTime()) / 1_000_000).toInt().coerceAtLeast(1)
            val packet = DatagramPacket(ByteArray(4096), 4096)
            try { socket.receive(packet) } catch (_: java.net.SocketTimeoutException) { break }
            val candidates = plexGdmConnections(String(packet.data, 0, packet.length, Charsets.UTF_8),
                packet.address.hostAddress.orEmpty(), machineId)
            if (candidates.isNotEmpty()) return@withContext candidates
        }
        emptyList()
    }
}

/** These are only candidates: a token-free HTTPS /identity probe must still match the saved server. */
internal fun plexGdmConnections(response: String, address: String, machineId: String): List<String> {
    val fields = response.lineSequence().mapNotNull { line ->
        val colon = line.indexOf(':')
        if (colon < 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
    }.toMap()
    if (fields["resource-identifier"] != machineId) return emptyList()
    if (!Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}").matches(address)) return emptyList()
    val port = fields["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return emptyList()
    val host = fields["host"]?.takeIf { Regex("[a-zA-Z0-9-]+\\.plex\\.direct").matches(it) }
        ?: return emptyList()
    return listOf("https://${address.replace('.', '-')}.$host:$port")
}
