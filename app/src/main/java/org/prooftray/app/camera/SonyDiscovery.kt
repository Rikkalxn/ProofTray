package org.prooftray.app.camera

import android.net.DnsResolver
import android.net.Network
import android.os.CancellationSignal
import android.os.SystemClock
import java.io.IOException
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okio.Buffer
import okio.BufferedSource
import okio.buffer
import okio.source
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

private const val SONY_IMAGING_SERVICE = "urn:schemas-sony-com:service:DigitalImaging:1"
private const val DESCRIPTION_LIMIT = 256L * 1024
private const val SEARCH_INTERVAL_MS = 3000L

internal data class SonyLanCamera(val id: UUID, val name: String, val host: String)
private data class DescriptionLocation(val address: Inet4Address, val uri: URI)

internal fun sonyDescriptionLocation(response: String, sender: String): URI? {
    val lines = response.lineSequence()
    if (!lines.first().startsWith("HTTP/1.1 200 ")) return null
    val location = lines.drop(1).takeWhile { it.isNotBlank() }.firstOrNull { it.substringBefore(':').equals("LOCATION", true) }
        ?.substringAfter(':')?.trim() ?: return null
    val uri = runCatching { URI(location) }.getOrNull() ?: return null
    val host = uri.host ?: return null
    if (host != sender && (host.all { it.isDigit() || it == '.' } || ':' in host)) return null
    return uri.takeIf { it.scheme.equals("http", true) && it.rawUserInfo == null && it.rawFragment == null && (it.port == -1 || it.port in 1..65535) }
}

internal fun sonyLanCamera(xml: String, host: String): SonyLanCamera? {
    if (xml.contains("<!DOCTYPE", ignoreCase = true)) return null
    val parser = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
    parser.setEntityResolver { _, _ -> throw SAXException("Unexpected external entity in camera description") }
    parser.setErrorHandler(DefaultHandler())
    val root = try { parser.parse(InputSource(StringReader(xml.removePrefix("\uFEFF")))).documentElement } catch (_: SAXException) { return null }
    fun Element.children(tag: String) = (0 until childNodes.length).asSequence().mapNotNull { childNodes.item(it) as? Element }
        .filter { it.localName == tag }
    val device = root.children("device").firstOrNull() ?: return null
    val services = device.children("serviceList").flatMap { it.children("service") }.flatMap { it.children("serviceType") }
    if (services.none { it.textContent.trim().equals(SONY_IMAGING_SERVICE, true) }) return null
    fun field(tag: String) = device.children(tag).firstOrNull()?.textContent?.trim().orEmpty()
    val udn = field("UDN")
    if (!udn.startsWith("uuid:", true)) return null
    val id = runCatching { UUID.fromString(udn.substring(5)) }.getOrNull() ?: return null
    return SonyLanCamera(id, field("friendlyName").ifBlank { field("modelName").ifBlank { "Sony α" } }, host)
}

private suspend fun Network.resolvesToResponder(host: String, sender: Inet4Address): Boolean {
    if (host == sender.hostAddress) return true
    return suspendCancellableCoroutine { waiting ->
        val cancellation = CancellationSignal()
        waiting.invokeOnCancellation { cancellation.cancel() }
        val callback = object : DnsResolver.Callback<List<InetAddress>> {
            override fun onAnswer(answer: List<InetAddress>, rcode: Int) = waiting.resume(rcode == 0 && sender in answer)
            override fun onError(error: DnsResolver.DnsException) = waiting.resume(false)
        }
        DnsResolver.getInstance().query(this, host, DnsResolver.FLAG_EMPTY, Runnable::run, cancellation, callback)
    }
}

/** Read the camera's local HTTP description on its Wi-Fi socket, with no proxy or redirects. */
private suspend fun loadDescription(network: Network, location: DescriptionLocation): SonyLanCamera? = coroutineScope {
    val uri = location.uri
    if (!network.resolvesToResponder(uri.host, location.address)) return@coroutineScope null
    network.socketFactory.createSocket().use { socket ->
        val closer = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { socket.close() } }
        try {
            socket.connect(InetSocketAddress(location.address, if (uri.port == -1) 80 else uri.port), 1800)
            val path = uri.rawPath.ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
            val request = "GET $path HTTP/1.1\r\nHost: ${uri.rawAuthority}\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            val source = socket.source().buffer()
            source.timeout().deadline(3, TimeUnit.SECONDS)
            val xml = readSonyDescriptionBody(source) ?: return@use null
            sonyLanCamera(xml, requireNotNull(location.address.hostAddress))
        } finally { closer.cancel() }
    }
}

internal fun readSonyDescriptionBody(source: BufferedSource): String? {
    if (source.readUtf8LineStrict(1024).split(' ').getOrNull(1) != "200") return null
    var chunked = false
    var length: Long? = null
    var headerBytes = 0L
    while (headerBytes < 16384) {
        val line = source.readUtf8LineStrict(4096)
        headerBytes += line.length + 2L
        if (line.isEmpty()) break
        val value = line.substringAfter(':', "").trim()
        when (line.substringBefore(':').lowercase()) {
            "content-length" -> length = value.toLongOrNull()?.takeIf { it in 0..DESCRIPTION_LIMIT } ?: return null
            "transfer-encoding" -> if (value.equals("chunked", true)) chunked = true else return null
        }
    }
    if (headerBytes >= 16384) return null
    val body = Buffer()
    if (chunked) {
        while (true) {
            val size = source.readUtf8LineStrict(128).substringBefore(';').trim().toLongOrNull(16) ?: return null
            if (size == 0L) break
            if (size !in 1..DESCRIPTION_LIMIT - body.size) return null
            source.readFully(body, size)
            if (source.readUtf8LineStrict(2).isNotEmpty()) return null
        }
    } else if (length != null) source.readFully(body, length)
    else {
        while (body.size <= DESCRIPTION_LIMIT && source.read(body, 8192) != -1L) { /* A close-delimited response. */ }
        if (body.size > DESCRIPTION_LIMIT) return null
    }
    return body.readUtf8()
}

internal fun discoverSonyCameras(network: Network): Flow<SonyLanCamera> = channelFlow<SonyLanCamera> {
    val locations = Channel<DescriptionLocation>(32)
    repeat(4) {
        launch {
            for (location in locations) {
                val camera = try { loadDescription(network, location) } catch (_: IOException) { null }
                if (camera != null) send(camera)
            }
        }
    }
    try {
        val group = InetAddress.getByName("239.255.255.250")
        val query = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: ssdp:all\r\n\r\n"
            .toByteArray(Charsets.US_ASCII)
        DatagramSocket(null).use { socket ->
            network.bindSocket(socket)
            socket.bind(InetSocketAddress(0))
            socket.soTimeout = 300
            val buffer = ByteArray(4096)
            val packet = DatagramPacket(buffer, buffer.size)
            val seen = mutableSetOf<URI>()
            var nextSearch = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val now = SystemClock.elapsedRealtime()
                if (now >= nextSearch) {
                    socket.send(DatagramPacket(query, query.size, group, 1900))
                    nextSearch = now + SEARCH_INTERVAL_MS
                    seen.clear()
                }
                packet.length = buffer.size
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                val address = packet.address as? Inet4Address ?: continue
                val host = address.hostAddress ?: continue
                if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress) continue
                val uri = sonyDescriptionLocation(String(buffer, 0, packet.length, Charsets.US_ASCII), host) ?: continue
                if (uri !in seen && seen.size < 64 && locations.trySend(DescriptionLocation(address, uri)).isSuccess) seen.add(uri)
            }
        }
    } finally { locations.close() }
}.flowOn(Dispatchers.IO)
