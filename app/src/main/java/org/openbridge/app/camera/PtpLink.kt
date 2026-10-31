package org.openbridge.app.camera

import android.graphics.BitmapFactory
import android.net.Network
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

internal class PtpResponseException(val operation: Int, val response: Int, val receivedBytes: Long = 0) : IOException(
    "PTP 操作 0x${operation.toString(16)} 返回 0x${response.toString(16)}"
)

/** Android-bound sockets; wire request construction and datasets are handled by the C++ codec. */
internal class PtpLink(
    private val network: Network?, private val host: String, private val port: Int, private val onDisconnected: (Throwable) -> Unit = {}
) : Closeable {
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    @Volatile private var commandSocket: Socket? = null
    @Volatile private var eventSocket: Socket? = null
    @Volatile var connected = false
        private set
    private var transaction = 0L
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream
    lateinit var device: JSONObject
        private set
    @Volatile private var operations = emptySet<Int>()
    private val rejectedPreviewOperations = mutableSetOf<Int>()
    private val catalogSignals = Channel<Unit>(Channel.CONFLATED)
    @Volatile var catalogRevision: Int = 0
        private set

    fun connect(identity: UUID) = synchronized(lock) {
        require(port in 1..65535 && host.isNotBlank()) { "相机地址或端口无效" }
        try {
            val socket = openSocket().also { commandSocket = it }
            input = DataInputStream(socket.getInputStream())
            output = socket.getOutputStream()
            val guid = ByteBuffer.allocate(16).putLong(identity.mostSignificantBits).putLong(identity.leastSignificantBits).array()
            output.write(NativePtp.initialize(guid, "OpenBridge"))
            output.flush()
            val (type, body) = frame(input, 1024 * 1024)
            if (type == 5) throw IOException("PTP/IP 握手被拒，原因码 ${body.uint(0)}；请检查相机连接模式和机身确认提示")
            check(type == 2 && body.size >= 24) { "PTP/IP 初始化响应无效" }
            val connection = body.uint(0)
            val events = openSocket().also { eventSocket = it }
            val eventInput = DataInputStream(events.getInputStream())
            events.getOutputStream().write(packet(3, uintBytes(connection)))
            val (eventType, _) = frame(eventInput, 1024 * 1024)
            check(eventType == 4) { "PTP/IP 事件通道未被相机接受" }
            transact(0x1002, longArrayOf(SecureRandom().nextInt(Int.MAX_VALUE).toLong() + 1), false, null, 0)
            transaction = 1
            device = JSONObject(NativePtp.info(data(0x1001), true))
            operations = device.getJSONArray("operations").let { values -> (0 until values.length()).map { values.getInt(it) }.toSet() }
            rejectedPreviewOperations.clear()
            connected = true
            events.soTimeout = 0
            Thread({
                try {
                    while (connected) {
                        val (kind, payload) = frame(eventInput, 1024 * 1024)
                        if (kind == 13) events.getOutputStream().write(packet(14))
                        if (kind == 8 && payload.size >= 6) {
                            val code = (payload[0].toInt() and 255) or ((payload[1].toInt() and 255) shl 8)
                            if (code in setOf(0x4004, 0x4005, 0x400b)) catalogRevision++
                            if (code in setOf(0x4002, 0x4003, 0x4004, 0x4005, 0x400b, 0x400d, 0xc101, 0xc102)) catalogSignals.trySend(Unit)
                        }
                    }
                } catch (error: Exception) {
                    if (connected) Log.w("OpenBridgeCamera", "PTP event channel ended", error)
                    closeWithCause(error)
                }
            }, "OpenBridge-PTP-events").apply { isDaemon = true; start() }
        } catch (error: Exception) {
            close()
            throw IOException("连接相机失败：${error.message}", error)
        }
    }

    fun storageIds(): LongArray = synchronized(lock) { NativePtp.handles(data(0x1004)) }
    fun objectHandles(storage: Long): LongArray = synchronized(lock) { NativePtp.handles(data(0x1007, longArrayOf(storage, 0, 0))) }
    fun objectInfo(handle: Long): JSONObject = synchronized(lock) {
        val info = JSONObject(NativePtp.info(data(0x1008, longArrayOf(handle)), false))
        if (info.getLong("size") == 0xffff_ffffL && 0x9803 in operations) {
            try {
                val size = data(0x9803, longArrayOf(handle, 0xdc04))
                if (size.size == 8) info.put("size", ByteBuffer.wrap(size).order(ByteOrder.LITTLE_ENDIAN).long)
            } catch (_: PtpResponseException) { /* Unknown size remains explicitly unsupported. */ }
        }
        info
    }
    fun supportsPartial(size: Long): Boolean = 0x9431 in operations || (0x101b in operations && size < 0xffff_ffffL)
    fun ping() = synchronized(lock) { data(0x1001); Unit }
    fun supportsLocation(): Boolean = 0x9523 in operations

    suspend fun awaitCatalogChange(milliseconds: Long) {
        withTimeoutOrNull(milliseconds) { catalogSignals.receiveCatching() }
    }

    fun updateLocation(payload: ByteArray) = synchronized(lock) {
        require(payload.size == 41)
        check(supportsLocation()) { "相机未报告 Wi-Fi 位置同步能力" }
        try { transact(0x9523, longArrayOf(), false, null, 0, outgoing = payload) }
        catch (error: PtpResponseException) {
            if (error.response == 0x2005) operations = operations - 0x9523
            throw error
        }
        Unit
    }

    fun portableJpeg(handle: Long): ByteArray = synchronized(lock) {
        // FULL_HD/MPF_CLASS2 is a camera-rendered copy, including for supported RAW-only captures.
        for (operation in listOf(0x9522, 0x920f)) {
            if (operation !in operations) continue
            try {
                val parameters = if (operation == 0x9522) longArrayOf(handle, 3, 0) else longArrayOf(handle)
                val bytes = data(operation, parameters)
                check(bytes.size > 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()) {
                    "相机返回的便携图像不是 JPEG，可能为 HEIF；请选择原格式下载"
                }
                return@synchronized bytes
            } catch (error: PtpResponseException) {
                if (error.response !in setOf(0x2005, 0x200a, 0x201d)) throw error
                if (error.response == 0x2005) operations = operations - operation
            }
        }
        error("相机不支持便携 JPEG 下载，请选择原格式；不会用小缩略图替代")
    }

    fun thumbnail(handle: Long, destination: OutputStream): Long = synchronized(lock) {
        transact(0x100a, longArrayOf(handle), true, destination, 16L * 1024 * 1024)
    }

    fun preview(handle: Long, detailed: Boolean): CameraPreview = synchronized(lock) {
        val nikon = device.optInt("vendor") == 10 || device.optString("manufacturer").startsWith("NIKON", ignoreCase = true)
        val candidates = previewCommands(detailed, operations, nikon, rejectedPreviewOperations)
        val level = if (detailed) "detail" else "grid"
        var best: CameraPreview? = null
        for (request in candidates) {
            if (request.operation == GET_THUMB && best != null) break
            try {
                val bytes = data(request.operation, request.parameters(handle))
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val dimensions = PreviewDimensions(bounds.outWidth, bounds.outHeight)
                Log.i("OpenBridgePreview", "$level op=0x${request.operation.toString(16)} advertised=${request.operation in operations}" +
                    " size=${dimensions.width}x${dimensions.height} bytes=${bytes.size}")
                if (!dimensions.valid) continue
                if (dimensions.improves(best?.dimensions)) best = CameraPreview(bytes, dimensions, request.operation)
                if (dimensions.sufficient(detailed)) return@synchronized requireNotNull(best)
            } catch (error: PtpResponseException) {
                Log.i("OpenBridgePreview", "$level op=0x${request.operation.toString(16)} response=0x${error.response.toString(16)}")
                if (!canTryAnotherPreview(error.response)) throw error
                if (error.response == 0x2005) rejectedPreviewOperations += request.operation
            }
        }
        best ?: error("相机没有返回可解码的预览；可尝试下载原片后查看")
    }

    fun partial(handle: Long, offset: Long, count: Int, destination: OutputStream): Long = synchronized(lock) {
        require(offset >= 0)
        require(count in 1..(4 * 1024 * 1024))
        if (offset <= 0xffff_ffffL && 0x101b in operations) {
            try {
                return@synchronized transact(0x101b, longArrayOf(handle, offset, count.toLong()), true, destination, count.toLong())
            } catch (error: PtpResponseException) {
                if (error.response != 0x2005) throw error
                operations = operations - 0x101b
                if (error.receivedBytes > 0) throw error
            }
        }
        check(0x9431 in operations) { "分段读取不可用；重试时将改为整文件传输" }
        try {
            transact(0x9431, longArrayOf(handle, offset and 0xffff_ffffL, offset ushr 32, count.toLong(), 0), true, destination, count.toLong())
        } catch (error: PtpResponseException) {
            if (error.response == 0x2005) operations = operations - 0x9431
            throw error
        }
    }

    fun registrationCode(): String = synchronized(lock) {
        check(0x952b in operations && 0x935a in operations) { "这台相机未报告主机登记能力，请使用机身连接向导" }
        val bytes = data(0x952b)
        val count = if (bytes.size >= 4) bytes.uint(0).toInt() else 0
        check(count in 1..16 && bytes.size >= 4 + count && bytes.copyOfRange(4, 4 + count).all { it.toInt() in 0..9 }) {
            "机身配对码格式尚未适配，请勿自动确认；保留当前机身提示以便调试"
        }
        bytes.copyOfRange(4, 4 + count).joinToString("") { it.toString() }
    }

    fun confirmRegistration() = synchronized(lock) { transact(0x935a, longArrayOf(0x2001), false, null, 0); Unit }

    fun original(handle: Long, destination: OutputStream, maximum: Long, progress: (Long) -> Unit): Long = synchronized(lock) {
        transact(0x1009, longArrayOf(handle), true, destination, maximum, progress = progress)
    }

    private fun data(operation: Int, params: LongArray = longArrayOf()): ByteArray {
        val bytes = ByteArrayOutputStream()
        transact(operation, params, true, bytes, 16L * 1024 * 1024)
        return bytes.toByteArray()
    }

    private fun transact(
        operation: Int, params: LongArray, receive: Boolean, sink: OutputStream?, maximum: Long,
        outgoing: ByteArray? = null, progress: (Long) -> Unit = {}
    ): Long {
        val id = transaction
        if (transaction > 0) transaction++
        var declared = -1L
        var received = 0L
        var ended = false
        var lastType = -1
        val tracePreview = operation == GET_FHD_PICTURE || operation == GET_LSS_IMAGE
        try {
            output.write(NativePtp.command(operation, id, params, receive, outgoing != null))
            if (outgoing != null) {
                check(!receive && outgoing.size <= 4096) { "PTP 发送数据大小无效" }
                val start = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(id.toInt()).putLong(outgoing.size.toLong()).array()
                output.write(packet(9, start))
                output.write(packet(12, uintBytes(id) + outgoing))
            }
            output.flush()
            val scratch = ByteArray(64 * 1024)
            while (true) {
                val header = ByteArray(8).also { input.readFully(it) }
                val length = header.uint(0)
                val type = header.uint(4).toInt()
                lastType = type
                if (length < 8 || (type != 10 && type != 12 && length > 1024 * 1024L)) throw IOException("PTP/IP 控制包长度越界：$length")
                var remaining = length - 8
                when (type) {
                    9 -> {
                        check(remaining in setOf(8L, 12L) && declared < 0 && receive) { "重复或无效的数据开始包" }
                        val start = ByteArray(remaining.toInt()).also { input.readFully(it) }
                        check(start.uint(0) == id) { "PTP 事务号不匹配" }
                        declared = if (start.size == 12) ByteBuffer.wrap(start, 4, 8).order(ByteOrder.LITTLE_ENDIAN).long else start.uint(4)
                        check(declared >= 0 && declared <= maximum) { "相机报告的传输大小超出限制：$declared" }
                        if (tracePreview) Log.i("OpenBridgePreview", "wire op=0x${operation.toString(16)} start declared=$declared")
                    }
                    10, 12 -> {
                        check(remaining >= 4 && declared >= 0 && !ended && sink != null) { "PTP 数据包顺序错误" }
                        val tid = ByteArray(4).also { input.readFully(it) }.uint(0)
                        check(tid == id) { "PTP 数据事务号不匹配" }
                        remaining -= 4
                        check(remaining <= declared - received) { "相机发送的数据超过声明长度" }
                        while (remaining > 0) {
                            val count = minOf(remaining, scratch.size.toLong()).toInt()
                            input.readFully(scratch, 0, count)
                            sink.write(scratch, 0, count)
                            received += count
                            remaining -= count
                            progress(received)
                        }
                        if (type == 12) {
                            ended = true
                            if (tracePreview) Log.i("OpenBridgePreview",
                                "wire op=0x${operation.toString(16)} end received=$received declared=$declared")
                        }
                    }
                    7 -> {
                        check(remaining in 6..26 && (remaining - 6) % 4 == 0L) { "PTP 响应包无效" }
                        val response = ByteArray(remaining.toInt()).also { input.readFully(it) }
                        check(response.uint(2) == id) { "PTP 响应事务号不匹配" }
                        val code = (response[0].toInt() and 255) or ((response[1].toInt() and 255) shl 8)
                        if (tracePreview) Log.i("OpenBridgePreview", "wire op=0x${operation.toString(16)} response=0x${code.toString(16)}")
                        if (code != 0x2001) throw PtpResponseException(operation, code, received)
                        check(!receive || (ended && received == declared)) { "数据尚未完整接收，相机提前返回成功" }
                        return received
                    }
                    13 -> { check(remaining == 0L); output.write(packet(14)); output.flush() }
                    else -> throw IOException("未预期的 PTP/IP 包类型：$type")
                }
            }
        } catch (error: PtpResponseException) {
            throw error
        } catch (error: Exception) {
            val failure = IOException("传输中断（操作 0x${operation.toString(16)}，$received/$declared 字节，结束包=$ended，末包=$lastType）：" +
                (error.message ?: error.javaClass.simpleName), error)
            closeWithCause(failure)
            throw failure
        }
    }

    private fun openSocket(): Socket {
        val socket = network?.socketFactory?.createSocket() ?: Socket()
        try {
            socket.soTimeout = 15000
            socket.tcpNoDelay = true
            val address = network?.getAllByName(host)?.firstOrNull()
            socket.connect(if (address != null) InetSocketAddress(address, port) else InetSocketAddress(host, port), 15000)
            return socket
        } catch (error: Exception) { socket.close(); throw error }
    }

    override fun close() = closeWithCause(null)

    private fun closeWithCause(error: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        val notify = connected && error != null
        connected = false
        catalogSignals.close()
        runCatching { commandSocket?.close() }
        runCatching { eventSocket?.close() }
        if (notify) onDisconnected(requireNotNull(error))
    }

    private fun frame(stream: DataInputStream, maximum: Int): Pair<Int, ByteArray> {
        val header = ByteArray(8).also { stream.readFully(it) }
        val size = header.uint(0)
        if (size !in 8..maximum.toLong()) throw IOException("握手或事件包长度无效")
        return header.uint(4).toInt() to ByteArray(size.toInt() - 8).also { stream.readFully(it) }
    }

    private fun packet(type: Int, payload: ByteArray = byteArrayOf()): ByteArray =
        ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN).putInt(8 + payload.size).putInt(type).put(payload).array()

    private fun uintBytes(value: Long): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()
    private fun ByteArray.uint(offset: Int): Long {
        if (offset < 0 || size - offset < 4) throw EOFException("PTP 字段不完整")
        return ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffff_ffffL
    }
}
