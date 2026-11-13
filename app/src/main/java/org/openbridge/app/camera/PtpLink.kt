package org.openbridge.app.camera

import android.net.Network
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.openbridge.app.data.PreviewImageInfo
import org.openbridge.app.domain.MediaFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** Android-bound sockets; wire request construction and datasets are handled by the C++ codec. */
internal class PtpLink(
    network: Network?, private val host: String, private val port: Int, private val onDisconnected: (Throwable) -> Unit = {},
    sonyAccess: SonyAccess? = null, private val expectedMake: CameraMake = CameraMake.OTHER, private val diagnostic: (String) -> Unit = {}
) : Closeable {
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val transport = PtpTransport(network, host, port, sonyAccess)
    @Volatile var make = CameraMake.OTHER
        private set
    @Volatile var connected = false
        private set
    private var transaction = 0L
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream
    lateinit var device: JSONObject
        private set
    @Volatile private var operations = emptySet<Int>()
    private var sonyContents: SonyContentsCatalog? = null
    private var sonyMtpTransfer = false
    private var sonyProperties = emptySet<Int>()
    private var sonyReportSize = false
    private var sonyOriginal: SonyMediaFile? = null
    private var sonyReadStamp: Long? = null
    private val rejectedPreviewOperations = mutableSetOf<Int>()
    private val catalogSignals = Channel<Unit>(Channel.CONFLATED)
    @Volatile var catalogRevision: Int = 0
        private set

    fun connect(identity: UUID) = synchronized(lock) {
        require(port in 1..65535 && host.isNotBlank()) { "相机地址或端口无效" }
        try {
            val command = transport.open()
            input = command.input
            output = command.output
            val guid = ByteBuffer.allocate(16).putLong(identity.mostSignificantBits).putLong(identity.leastSignificantBits).array()
            output.write(NativePtp.initialize(guid, "ProofTray"))
            output.flush()
            val (type, body) = frame(input, 1024 * 1024)
            if (type == 5) {
                diagnostic("PTP/IP InitFail 0x${body.uint(0).toString(16)}")
                throw IOException("机身拒绝了连接，请检查相机的连接模式和确认提示")
            }
            check(type == 2 && body.size >= 24) { "PTP/IP 初始化响应无效" }
            val connection = body.uint(0)
            val events = transport.open()
            val eventInput = events.input
            events.output.write(packet(3, uintBytes(connection)))
            events.output.flush()
            val (eventType, _) = frame(eventInput, 1024 * 1024)
            check(eventType == 4) { "PTP/IP 事件通道未被相机接受" }
            if (expectedMake != CameraMake.NIKON) {
                try { readDevice() } catch (error: PtpResponseException) {
                    if (expectedMake != CameraMake.OTHER || error.response != 0x2003) throw error
                }
            }
            if (expectedMake == CameraMake.SONY) check(make == expectedMake) { "Wi-Fi 设备与已配对的索尼相机不符" }
            if (make == CameraMake.SONY) {
                openSonyImport()
            } else {
                transact(0x1002, longArrayOf(SecureRandom().nextInt(Int.MAX_VALUE).toLong() + 1), false, null, 0)
                transaction = 1
                readDevice()
                check(make != CameraMake.SONY) { "请从相机页选择这台索尼机身，重新开启智能手机传输会话" }
            }
            rejectedPreviewOperations.clear()
            connected = true
            events.listenForEvents()
            Thread({
                try {
                    while (connected) {
                        val (kind, payload) = frame(eventInput, 1024 * 1024)
                        if (kind == 13) { events.output.write(packet(14)); events.output.flush() }
                        if (kind == 8 && payload.size >= 6) {
                            val code = (payload[0].toInt() and 255) or ((payload[1].toInt() and 255) shl 8)
                            val sonyChanged = make == CameraMake.SONY && code == SONY_CONTENTS_CHANGED
                            if (code in setOf(0x4004, 0x4005, 0x400b) || sonyChanged) catalogRevision++
                            if (code in setOf(0x4002, 0x4003, 0x4004, 0x4005, 0x400b, 0x400d, 0xc101, 0xc102)) catalogSignals.trySend(Unit)
                            if (make == CameraMake.SONY && code in setOf(0xc201, 0xc202, 0xc203, 0xc20d, SONY_CONTENTS_CHANGED)) {
                                catalogSignals.trySend(Unit)
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (connected) Log.w("OpenBridgeCamera", "PTP event channel ended", error)
                    closeWithCause(error)
                }
            }, "ProofTray-PTP-events").apply { isDaemon = true; start() }
        } catch (error: Exception) {
            close()
            throw IOException("连接相机失败：${error.message}", error)
        }
    }

    private fun readDevice() {
        device = JSONObject(NativePtp.info(data(0x1001), true))
        make = ptpCameraMake(device.optString("manufacturer"), device.optInt("vendor"))
        operations = device.getJSONArray("operations").let { values -> (0 until values.length()).map { values.getInt(it) }.toSet() }
    }

    private fun openSonyImport() {
        // GetDeviceInfo used transaction 0. Sony keeps incrementing through descriptions and session opening, without a reset.
        transaction = 1
        // Both device descriptions are read before choosing a function mode, as in the Creators' App initializer.
        val description = if (0x923a in operations) {
            val dd = sonyDescription(data(0x923a, longArrayOf(1), 1024 * 1024L))
            val did = sonyDescription(data(0x923a, longArrayOf(2), 1024 * 1024L))
            SonyDescription(did.server ?: dd.server, did.transfer ?: dd.transfer)
        } else SonyDescription()
        val contentsMode = (description.server ?: 0.0) >= 4.0
        check(!contentsMode || !description.transfer.equals("Disable", true)) { "机身当前未开放照片传输，请检查智能手机连接设置" }
        if (contentsMode || 0x9210 in operations) transact(0x9210, longArrayOf(1, if (contentsMode) 2 else 1), false, null, 0)
        else transact(0x1002, longArrayOf(1), false, null, 0)
        if (0x9201 in operations && 0x9202 in operations) {
            fun connectPhase(phase: Long) {
                transact(0x9201, longArrayOf(phase, 0, 0), false, ByteArrayOutputStream(), 64 * 1024L)
            }
            connectPhase(1)
            connectPhase(2)
            var version = 0L
            if (0x9216 in operations) transact(0x9216, longArrayOf(), false, null, 0, onResponse = { version = it.firstOrNull() ?: 0 })
            val parameters = if (version >= 310) longArrayOf(300, 1) else longArrayOf(300)
            for (attempt in 1..20) {
                val ext = data(0x9202, parameters)
                if (ext.size >= 10) { sonyProperties = sonyExtendedProperties(ext); break }
                check(attempt < 20) { "索尼相机尚未准备好传输，请退出机身菜单后重试" }
                Thread.sleep(100)
            }
            connectPhase(3)
        }
        Log.i("OpenBridgeCamera", "Sony import: server=${description.server} catalog=${if (contentsMode) "contents" else "MTP"}")
        if (contentsMode) {
            sonyReportSize = sonyProperty(0xe116)?.supportsFileSizeReport == true
            sonyContents = SonyContentsCatalog({ op, params -> data(op, params) }, ::sonySlots)
            return
        }
        if (0x9212 in operations) {
            transact(0x9212, longArrayOf(2, 1, 0), false, null, 0)
            sonyMtpTransfer = true
        }
        for (attempt in 1..25) {
            try { if (storageIds().isNotEmpty()) return }
            catch (error: PtpResponseException) { if (error.response != 0x2013 && error.response != 0x2019) throw error }
            if (attempt < 25) Thread.sleep(300)
        }
        error("索尼相机已连接，但存储卡尚未就绪，请检查机身或重试")
    }

    fun finishSonyImport() = synchronized(lock) {
        if (!connected || make != CameraMake.SONY) return@synchronized
        if (sonyMtpTransfer) transact(0x9212, longArrayOf(2, 0, 1), false, null, 0)
        transact(0x1003, longArrayOf(), false, null, 0)
        Unit
    }

    private fun sonyProperty(code: Int): SonyScalar? {
        if (0x9251 !in operations || code !in sonyProperties) return null
        return try { sonyScalar(data(0x9251, longArrayOf(code.toLong())), code) }
        catch (error: PtpResponseException) {
            if (error.response !in setOf(0x2005, 0x200a, 0x201d)) throw error
            null
        }
    }

    private fun sonySlots(): List<Int> {
        val advertised = (1..2).filter { 0xd1d3 + it in sonyProperties }
        val states = advertised.ifEmpty { listOf(1, 2) }.map { slot -> slot to sonyProperty(0xd1d3 + slot) }
        val readable = states.filter { (_, state) -> state == null || state.contentsReadable }.map { it.first }
        if (readable.isEmpty()) {
            val detail = "Sony 卡槽状态：" + states.joinToString { (slot, state) ->
                "slot=$slot enabled=${state?.enabled} value=0x${state?.value?.toString(16)}"
            }
            Log.i("OpenBridgeCamera", detail)
            diagnostic(detail)
        }
        return readable
    }

    fun sonyCatalogCursor(storage: Long? = null, direction: CatalogDirection = CatalogDirection.NEWEST,
        startAt: SonyCaptureDay? = null): SonyContentsCatalog.Cursor? = synchronized(lock) {
        sonyContents?.cursor(storage?.toInt(), direction, startAt)
    }
    fun nextSonyPage(cursor: SonyContentsCatalog.Cursor): List<Long> = synchronized(lock) { cursor.next() }
    fun storageIds(): LongArray = synchronized(lock) {
        if (sonyContents != null) sonySlots().map(Int::toLong).toLongArray() else NativePtp.handles(data(0x1004))
    }
    fun objectHandles(storage: Long): LongArray = synchronized(lock) {
        check(sonyContents == null) { "索尼内容目录需要按拍摄日期读取" }
        NativePtp.handles(data(0x1007, longArrayOf(storage, 0, 0)))
    }
    fun objectInfo(handle: Long, verify: Boolean = false): JSONObject = synchronized(lock) {
        sonyContents?.let { catalog ->
            val file = if (verify) catalog.verify(handle).also { sonyOriginal = it; sonyReadStamp = null } else catalog.file(handle)
            return@synchronized file.info()
        }
        val info = JSONObject(NativePtp.info(data(0x1008, longArrayOf(handle)), false))
        if (info.getLong("size") == 0xffff_ffffL && 0x9803 in operations) {
            try {
                val size = data(0x9803, longArrayOf(handle, 0xdc04))
                if (size.size == 8) info.put("size", ByteBuffer.wrap(size).order(ByteOrder.LITTLE_ENDIAN).long)
            } catch (_: PtpResponseException) { /* Unknown size remains explicitly unsupported. */ }
        }
        info
    }
    private fun largePartialOp(): Int? = 0x9431.takeIf { make == CameraMake.NIKON && it in operations }

    val chunkSize: Int get() = if (make == CameraMake.SONY) SONY_CHUNK else 1024 * 1024
    fun supportsPartial(size: Long): Boolean = make == CameraMake.SONY || largePartialOp() != null || (0x101b in operations && size < 0xffff_ffffL)
    fun ping() = synchronized(lock) { data(0x1001); Unit }
    fun supportsLocation(): Boolean = make == CameraMake.NIKON && 0x9523 in operations

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
        check(make == CameraMake.NIKON) { "请在导入列表选择机身 JPEG 或 RAW 原片" }
        // FULL_HD/MPF_CLASS2 is a camera-rendered copy, including for supported RAW-only captures.
        for (operation in listOf(0x9522, 0x920f)) {
            if (operation !in operations) continue
            try {
                val parameters = if (operation == 0x9522) longArrayOf(handle, 3, 0) else longArrayOf(handle)
                val bytes = data(operation, parameters)
                check(bytes.size > 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()) {
                    "相机返回的便携图像不是 JPEG，可能为 HEIF；请选择原格式导入"
                }
                return@synchronized bytes
            } catch (error: PtpResponseException) {
                if (error.response !in setOf(0x2005, 0x200a, 0x201d)) throw error
                if (error.response == 0x2005) operations = operations - operation
            }
        }
        error("相机不支持便携 JPEG 导入，请选择原格式；不会用小缩略图替代")
    }

    fun preview(photo: RemotePhoto, detailed: Boolean): CameraPreview = synchronized(lock) {
        val handle = photo.handle
        if (make == CameraMake.SONY) return@synchronized sonyPreview(photo, detailed)
        val nikon = make == CameraMake.NIKON
        val candidates = previewCommands(detailed, operations, nikon, rejectedPreviewOperations)
        val level = if (detailed) "detail" else "grid"
        var best: CameraPreview? = null
        for (request in candidates) {
            if (request.operation == GET_THUMB && best != null) break
            try {
                val bytes = data(request.operation, request.parameters(handle))
                val dimensions = PreviewImageInfo.read(bytes)?.dimensions ?: continue
                Log.i("OpenBridgePreview", "$level op=0x${request.operation.toString(16)} advertised=${request.operation in operations}" +
                    " size=${dimensions.width}x${dimensions.height} bytes=${bytes.size}")
                if (dimensions.improves(best?.dimensions)) best = CameraPreview(bytes, dimensions, request.operation)
                if (dimensions.sufficient(detailed)) return@synchronized requireNotNull(best)
            } catch (error: PtpResponseException) {
                Log.i("OpenBridgePreview", "$level op=0x${request.operation.toString(16)} response=0x${error.response.toString(16)}")
                if (!canTryAnotherPreview(error.response)) throw error
                if (error.response == 0x2005) rejectedPreviewOperations += request.operation
            }
        }
        best ?: error("相机没有返回可解码的预览；可尝试导入原片后查看")
    }

    /** Read only the start of a Nikon original when a visible preview needs its EXIF. Never fall back to GetObject here. */
    fun nikonPhotoHeader(photo: RemotePhoto): ByteArray? = synchronized(lock) {
        val size = photo.asset.sizeBytes
        if (make != CameraMake.NIKON || photo.asset.format !in setOf(MediaFormat.JPEG, MediaFormat.RAW) ||
            size <= 0 || !supportsPartial(size)) return@synchronized null
        val count = minOf(size, 64L * 1024).toInt()
        val bytes = ByteArrayOutputStream(count)
        try {
            partial(photo.handle, 0, count, bytes, firstRead = true)
        } catch (_: PtpResponseException) { return@synchronized null }
        catch (unsupported: IllegalStateException) { if (!supportsPartial(size)) return@synchronized null else throw unsupported }
        bytes.toByteArray()
    }

    private fun sonyPreview(photo: RemotePhoto, detailed: Boolean): CameraPreview {
        val handle = photo.handle
        val file = sonyContents?.file(handle)
        if (file != null) {
            check(cameraObjectId(file.info(), photo.cameraIdentity) == photo.asset.id) {
                "照片目录已更换，请刷新后查看预览"
            }
        }
        val requests = when {
            file != null -> buildList {
                add(SONY_PREVIEW to file.ref.preview(detailed))
                if (detailed) add(SONY_PREVIEW to file.ref.preview(false))
            }
            else -> buildList {
                if (0x9803 !in rejectedPreviewOperations) {
                    if (detailed) add(0x9803 to longArrayOf(handle, SONY_SCREEN_PROP.toLong()))
                    add(0x9803 to longArrayOf(handle, SONY_THUMB_PROP.toLong()))
                }
                add(GET_THUMB to longArrayOf(handle))
            }
        }
        var best: CameraPreview? = null
        for ((op, params) in requests) {
            if (op in rejectedPreviewOperations) continue
            val response = try { data(op, params) } catch (error: PtpResponseException) {
                if (op == 0x9803 && error.response == 0x2005) rejectedPreviewOperations += op
                if (canTryAnotherPreview(error.response) || error.response in setOf(0xa801, 0xa80a)) continue
                throw error
            }
            val bytes = if (op == 0x9803) sonyPreviewProperty(response) ?: continue else response
            val info = PreviewImageInfo.read(bytes) ?: continue
            val extension = if (info.mimeType.startsWith("image/hei")) "heic" else "jpg"
            if (info.dimensions.improves(best?.dimensions)) best = CameraPreview(bytes, info.dimensions, op, extension)
            if (info.dimensions.sufficient(detailed)) return checkNotNull(best)
        }
        return best ?: error("机身未提供可显示的预览，可导入原片查看")
    }

    fun partial(handle: Long, offset: Long, count: Int, destination: OutputStream, firstRead: Boolean): Long = synchronized(lock) {
        require(offset >= 0)
        require(count in 1..(4 * 1024 * 1024))
        if (make == CameraMake.SONY) {
            val file = sonyContents?.file(handle)
            if (file != null) return@synchronized readSonyFile(file, offset, count, destination, firstRead)
            val args = longArrayOf(handle, offset and 0xffff_ffffL, offset ushr 32, SONY_CHUNK.toLong())
            return@synchronized transact(0x9211, args, true, destination, count.toLong())
        }
        if (offset <= 0xffff_ffffL && 0x101b in operations) {
            try {
                return@synchronized transact(0x101b, longArrayOf(handle, offset, count.toLong()), true, destination, count.toLong())
            } catch (error: PtpResponseException) {
                if (error.response != 0x2005) throw error
                operations = operations - 0x101b
                if (error.receivedBytes > 0) throw error
            }
        }
        val operation = checkNotNull(largePartialOp()) { "分段读取不可用；重试时将改为整文件传输" }
        val parameters = longArrayOf(handle, offset and 0xffff_ffffL, offset ushr 32, count.toLong())
        try {
            transact(operation, if (make == CameraMake.NIKON) parameters + 0L else parameters, true, destination, count.toLong())
        } catch (error: PtpResponseException) {
            if (error.response == 0x2005) operations = operations - operation
            throw error
        }
    }

    private fun readSonyFile(file: SonyMediaFile, offset: Long, count: Int, destination: OutputStream, firstRead: Boolean): Long {
        check(file == sonyOriginal) { "传输中的原片已重新编目，请刷新后重新选择" }
        check(offset < file.size && count <= file.size - offset) { "读取范围超出原片大小，请刷新导入列表" }
        val reportSize = firstRead && sonyReportSize
        val args = file.ref.chunk(offset, count, reportSize)
        var metadata = longArrayOf()
        val received = transact(SONY_FILE, args, true, destination, count.toLong(), onResponse = { metadata = it })
        validateSonyReadMetadata(metadata, file.size, sonyReadStamp ?: file.modifiedUtc, reportSize)?.let { sonyReadStamp = it }
        if (offset + received == file.size) {
            try {
                finishSonyPayload(offset + received, file.size) {
                    transact(SONY_FILE, file.ref.finish(), false, ByteArrayOutputStream(1), 1)
                }?.let { Log.i("OpenBridgeCamera", "Sony file fully received; final release acknowledgement unavailable", it) }
            } finally { sonyOriginal = null; sonyReadStamp = null }
        }
        return received
    }

    fun registrationCode(): String = synchronized(lock) {
        check(make == CameraMake.NIKON && 0x952b in operations && 0x935a in operations) { "这台相机未报告主机登记能力，请使用机身连接向导" }
        val bytes = data(0x952b)
        val count = if (bytes.size >= 4) bytes.uint(0).toInt() else 0
        check(count in 1..16 && bytes.size >= 4 + count && bytes.copyOfRange(4, 4 + count).all { it.toInt() in 0..9 }) {
            "机身配对码格式尚未适配，请勿自动确认；保留当前机身提示以便调试"
        }
        bytes.copyOfRange(4, 4 + count).joinToString("") { it.toString() }
    }

    fun confirmRegistration() = synchronized(lock) {
        check(make == CameraMake.NIKON && 0x935a in operations) { "当前相机未提供尼康主机登记" }
        transact(0x935a, longArrayOf(0x2001), false, null, 0)
        Unit
    }

    fun original(handle: Long, destination: OutputStream, maximum: Long, progress: (Long) -> Unit): Long = synchronized(lock) {
        check(make != CameraMake.SONY) { "索尼原片需要分段读取" }
        transact(0x1009, longArrayOf(handle), true, destination, maximum, progress = progress)
    }

    private fun data(operation: Int, params: LongArray = longArrayOf(), maximum: Long = 16L * 1024 * 1024): ByteArray {
        val bytes = ByteArrayOutputStream()
        transact(operation, params, true, bytes, maximum)
        return bytes.toByteArray()
    }

    private fun transact(
        operation: Int, params: LongArray, receive: Boolean, sink: OutputStream?, maximum: Long,
        outgoing: ByteArray? = null, progress: (Long) -> Unit = {}, onResponse: ((LongArray) -> Unit)? = null
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
                        check(remaining in setOf(8L, 12L) && declared < 0 && sink != null) { "重复或无效的数据开始包" }
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
                        if (code != 0x2001) {
                            val detail = "PTP/IP　0x${operation.toString(16)} → 0x${code.toString(16)} · tid=$id"
                            Log.w("OpenBridgeCamera", detail)
                            diagnostic(detail)
                            throw PtpResponseException(operation, code, received)
                        }
                        check((!receive && declared < 0) || (ended && received == declared)) { "数据尚未完整接收，相机提前返回成功" }
                        onResponse?.invoke(LongArray((response.size - 6) / 4) { response.uint(6 + 4 * it) })
                        return received
                    }
                    13 -> { check(remaining == 0L); output.write(packet(14)); output.flush() }
                    else -> throw IOException("未预期的 PTP/IP 包类型：$type")
                }
            }
        } catch (error: PtpResponseException) {
            throw error
        } catch (cancelled: CancellationException) {
            closeWithCause(cancelled)
            throw cancelled
        } catch (error: Exception) {
            val detail = "PTP/IP 0x${operation.toString(16)} tid=$id stage=$lastType bytes=$received/$declared end=$ended"
            Log.w("OpenBridgeCamera", detail, error)
            diagnostic(detail)
            val message = if (error.message.orEmpty().contains("ENOSPC")) "手机空间不足，请腾出空间后重试"
                else "相机传输中断，请检查 Wi-Fi 连接后重试"
            val failure = IOException(message, error)
            closeWithCause(failure)
            throw failure
        }
    }

    override fun close() = closeWithCause(null)

    private fun closeWithCause(error: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        val notify = connected && error != null
        connected = false
        catalogSignals.close()
        transport.close()
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
