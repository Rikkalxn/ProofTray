package org.prooftray.app.camera

import android.net.Network
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val SONY_IMAGING_SERVICE = "urn:schemas-sony-com:service:DigitalImaging:1"

internal data class SonyAnnouncement(val id: UUID?)

internal fun sonyAnnouncement(response: String): SonyAnnouncement? {
    val lines = response.lineSequence()
    if (!lines.first().startsWith("HTTP/1.1 200 ")) return null
    val headers = lines.drop(1).takeWhile { it.isNotBlank() }.associate {
        it.substringBefore(':').trim().lowercase() to it.substringAfter(':', "").trim()
    }
    if (!SONY_IMAGING_SERVICE.equals(headers["st"], true)) return null
    val udn = headers["usn"]?.substringBefore("::").orEmpty()
    val id = if (udn.startsWith("uuid:", true)) runCatching { UUID.fromString(udn.substring(5)) }.getOrNull() else null
    return SonyAnnouncement(id)
}

/** Returning true stops discovery as soon as the paired camera is found. Addresses come from the incoming UDP packets. */
internal suspend fun discoverSonyCameras(network: Network, found: (String, UUID?) -> Boolean) {
    val group = InetAddress.getByName("239.255.255.250")
    val query = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: $SONY_IMAGING_SERVICE\r\n\r\n"
        .toByteArray(Charsets.US_ASCII)
    DatagramSocket(null).use { socket ->
        network.bindSocket(socket)
        socket.bind(InetSocketAddress(0))
        socket.soTimeout = 350
        socket.send(DatagramPacket(query, query.size, group, 1900))
        val buffer = ByteArray(4096)
        val packet = DatagramPacket(buffer, buffer.size)
        val end = SystemClock.elapsedRealtime() + 2200
        val seen = mutableSetOf<String>()
        while (SystemClock.elapsedRealtime() < end && seen.size < 32) {
            currentCoroutineContext().ensureActive()
            packet.length = buffer.size
            try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
            val address = packet.address as? Inet4Address ?: continue
            val announcement = sonyAnnouncement(String(buffer, 0, packet.length, Charsets.US_ASCII)) ?: continue
            val host = address.hostAddress ?: continue
            if (seen.add(host) && found(host, announcement.id)) return
        }
    }
}
