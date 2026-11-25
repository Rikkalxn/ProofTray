package org.prooftray.app.camera

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledFuture
import java.net.SocketTimeoutException
import org.json.JSONObject
import org.prooftray.app.domain.BluetoothRole

/** Short-lived classic Bluetooth data connection. BLE remains the separate, low-duty-cycle control channel. */
@SuppressLint("MissingPermission")
internal class BluetoothLssLink(context: Context, private val address: String, private val role: BluetoothRole) : Closeable {
    private data class Frame(val magic: Int, val kind: Int, val flag: Int, val payload: ByteArray)
    private class Reply(val operation: Int, val transaction: Long) {
        val result = CompletableFuture<ByteArray>()
        val bytes = ByteArrayOutputStream()
        var expected = 0L
        var continuation = false
    }

    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val timeout = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ProofTray-BT-timeout").apply { isDaemon = true }
    }
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var reply: Reply? = null
    @Volatile private var closed = false
    private lateinit var input: DataInputStream
    private var transaction = 0L
    lateinit var device: JSONObject
        private set
    private var operations = emptySet<Int>()
    @Volatile private var sessionOpened = false

    fun prepare() {
        require(role != BluetoothRole.AUTOMATIC) { "蓝牙连接方向尚未确定" }
        check(!closed) { "蓝牙传图准备已取消" }
        check(adapter != null && adapter.isEnabled) { "手机蓝牙未开启" }
        check(adapter.getRemoteDevice(address).bondState == BluetoothDevice.BOND_BONDED) { "请先在系统中配对所选传图设备" }
        if (role == BluetoothRole.WAIT_FOR_CAMERA) {
            server = adapter.listenUsingRfcommWithServiceRecord("ProofTray", UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"))
            if (closed) { server?.close(); error("蓝牙传图准备已取消") }
        }
    }

    fun connect(identity: ByteArray) {
        require(identity.size == 8)
        check(adapter != null && adapter.isEnabled) { "手机蓝牙未开启" }
        try {
            val connection = if (role == BluetoothRole.WAIT_FOR_CAMERA) {
                val listener = server ?: error("蓝牙监听尚未准备好")
                try { listener.accept(30_000) } finally { listener.close(); server = null }
            } else {
                clientSocket(adapter.getRemoteDevice(address)).also {
                    socket = it
                    withSocketDeadline { it.connect() }
                }
            }
            socket = connection
            if (closed) { connection.close(); error("蓝牙传图连接已取消") }
            check(connection.remoteDevice.address.equals(address, true)) { "连接的传图设备与所选相机不一致" }
            Log.i("ProofTray.Bluetooth", "RFCOMM connected; role=$role")
            input = DataInputStream(connection.inputStream)
            val start = ByteArray(17).also { SecureRandom().nextBytes(it); it[0] = 1; identity.copyInto(it, 9) }
            val challenge = withSocketDeadline {
                sendAuthentication(1, start.copyOfRange(1, 17))
                readAuthentication(2)
            }
            val response = NativePtp.pairReply(start, byteArrayOf(2) + challenge)
            withSocketDeadline {
                sendAuthentication(3, ByteArray(8) + response.copyOfRange(9, 17))
                readAuthentication(4)
            }
            Log.i("ProofTray.Bluetooth", "Bluetooth data authentication complete")
            check(!closed) { "蓝牙传图连接已超时" }
            Thread({ readResponses() }, "ProofTray-BT-data").apply { isDaemon = true; start() }
            device = JSONObject(NativePtp.info(command(0x1001), true))
            val values = device.getJSONArray("operations")
            operations = (0 until values.length()).map { values.getInt(it) }.toSet()
            command(0x1002, longArrayOf(1))
            sessionOpened = true
            transaction = 1
            operations = nikonOperations(operations) { op, params -> command(op, params) }
            Log.i("ProofTray.Bluetooth", "Bluetooth PTP session opened")
        } catch (error: Exception) {
            close()
            throw IOException("蓝牙传图通道建立失败：${error.message}", error)
        }
    }

    private inline fun <T> withSocketDeadline(block: () -> T): T {
        val alarm = timeout.schedule({ close() }, 30, TimeUnit.SECONDS)
        try { return block() } finally { alarm.cancel(false) }
    }

    private fun clientSocket(device: BluetoothDevice): BluetoothSocket {
        // Some cameras expose the data service on channel 1 without an SPP SDP record. Never relax Android's API restrictions.
        try {
            val method = BluetoothDevice::class.java.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
            val connection = method.invoke(device, 1) as BluetoothSocket
            Log.i("ProofTray.Bluetooth", "RFCOMM client using camera channel 1")
            return connection
        } catch (error: ReflectiveOperationException) {
            Log.i("ProofTray.Bluetooth", "Fixed RFCOMM channel unavailable; using SPP discovery (${error.javaClass.simpleName})")
        } catch (_: SecurityException) {
            Log.i("ProofTray.Bluetooth", "Fixed RFCOMM channel restricted; using SPP discovery")
        }
        return device.createInsecureRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"))
    }

    private fun sendAuthentication(stage: Int, payload: ByteArray) {
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort(0xa1a1.toShort())
            .putShort(stage.toShort()).putInt(payload.size).array()
        socket!!.outputStream.apply { write(header); write(payload); flush() }
    }

    private fun readAuthentication(stage: Int): ByteArray {
        val frame = frame()
        check(frame.magic == 0xa2a2 && (frame.kind or (frame.flag shl 8)) == stage && frame.payload.size == 16) {
            "蓝牙传图身份验证阶段 $stage 响应无效"
        }
        return frame.payload
    }

    fun startAutomatic() {
        check(0x9520 in operations && 0x9521 in operations && 0x9522 in operations && 0x1008 in operations) {
            "相机未报告蓝牙待传列表、小图或文件信息能力"
        }
        command(0x9520, longArrayOf(0x11))
    }

    fun finishAutomatic() { command(0x9520, longArrayOf(0x22)) }

    fun finishSession() {
        if (!sessionOpened || closed) return
        sessionOpened = false
        try { command(0x1003, timeoutSeconds = 2) }
        catch (error: Exception) { Log.i("ProofTray.Bluetooth", "PTP close not acknowledged; closing owned data socket", error) }
    }
    fun objectInfo(handle: Long): JSONObject = JSONObject(NativePtp.info(command(0x1008, longArrayOf(handle)), false))

    fun pendingImages(): BluetoothTransferSelection = bluetoothTransferSelection(command(0x9521, longArrayOf(0)))

    fun portableJpeg(handle: Long): ByteArray {
        var bytes = byteArrayOf()
        for (attempt in 0..2) {
            try { bytes = command(0x9522, longArrayOf(handle, 3, 0)); break }
            catch (error: PtpResponseException) {
                if (error.response != 0x2019 || attempt == 2) throw error
                Thread.sleep(250L * (attempt + 1))
            }
        }
        check(bytes.size > 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()) { "相机返回的便携副本不是 JPEG" }
        return bytes
    }

    @Synchronized
    private fun command(operation: Int, parameters: LongArray = longArrayOf(), timeoutSeconds: Long = 30): ByteArray {
        check(!closed && parameters.size <= 5) { "蓝牙传图会话已关闭" }
        val id = transaction
        if (transaction != 0L) transaction++
        val pending = Reply(operation, id)
        reply = pending
        var alarm: ScheduledFuture<*>? = null
        try {
            alarm = timeout.schedule({
                if (reply === pending && pending.result.completeExceptionally(SocketTimeoutException("蓝牙操作 0x${operation.toString(16)} 超时"))) close()
            }, timeoutSeconds, TimeUnit.SECONDS)
            val size = 12 + parameters.size * 4
            val packet = ByteBuffer.allocate(8 + size).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(0xa5a5.toShort()).put(1).put(1).putInt(size)
                .putInt(size).putShort(1).putShort(operation.toShort()).putInt(id.toInt())
            parameters.forEach { packet.putInt(it.toInt()) }
            socket!!.outputStream.apply { write(packet.array()); flush() }
            return pending.result.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (error: Exception) {
            val cause = if (error is ExecutionException) error.cause else error
            if (cause is PtpResponseException && cause.receivedBytes == 0L) throw cause
            close()
            throw IOException("蓝牙操作 0x${operation.toString(16)} 失败：${error.cause?.message ?: error.message}", error)
        } finally { alarm?.cancel(false); if (reply === pending) reply = null }
    }

    private fun readResponses() {
        try {
            while (!closed) {
                val frame = frame()
                check(frame.magic == 0xa5a5 && frame.kind in 1..2 && frame.flag in setOf(0, 1, 3)) { "蓝牙数据帧标识无效" }
                val current = reply
                if (frame.flag == 3) throw IOException("相机取消了蓝牙传输")
                if (current?.continuation == true && !(frame.kind == 2 && frame.flag == 1)) {
                    append(current, frame.payload)
                    current.continuation = frame.flag != 1
                    continue
                }
                check(frame.payload.size >= 12) { "蓝牙 PTP 容器不完整" }
                val data = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN)
                val size = data.int.toLong() and 0xffff_ffffL
                val type = data.short.toInt() and 65535
                val code = data.short.toInt() and 65535
                val id = data.int.toLong() and 0xffff_ffffL
                if (type == 4) continue  // BLE owns wake-up notifications; these events do not complete a command.
                check(current != null && current.transaction == id) { "蓝牙 PTP 事务号不匹配" }
                if (type == 2) {
                    check(code == current.operation && size in 12..(16L * 1024 * 1024 + 12)) { "蓝牙图片数据长度无效" }
                    current.expected = size - 12
                    append(current, frame.payload.copyOfRange(12, frame.payload.size))
                    current.continuation = frame.flag != 1
                } else {
                    check(type == 3 && size == frame.payload.size.toLong()) { "蓝牙 PTP 响应无效" }
                    if (code != 0x2001 && !nikonSessionAlreadyOpen(current.operation, code)) {
                        current.result.completeExceptionally(PtpResponseException(current.operation, code, current.bytes.size().toLong()))
                    }
                    else {
                        check(current.bytes.size().toLong() == current.expected) { "蓝牙图片尚未接收完整" }
                        current.result.complete(current.bytes.toByteArray())
                    }
                }
            }
        } catch (error: Exception) { reply?.result?.completeExceptionally(error); close() }
    }

    private fun append(reply: Reply, bytes: ByteArray) {
        check(bytes.size.toLong() <= reply.expected - reply.bytes.size()) { "蓝牙图片超过声明长度" }
        reply.bytes.write(bytes)
    }

    private fun frame(): Frame {
        val header = ByteArray(8).also { input.readFully(it) }
        val data = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val magic = data.short.toInt() and 65535
        val kind = data.get().toInt() and 255
        val flag = data.get().toInt() and 255
        val count = data.int.toLong() and 0xffff_ffffL
        check(count <= 16L * 1024 * 1024 + 12) { "蓝牙数据帧过大" }
        return Frame(magic, kind, flag, ByteArray(count.toInt()).also { input.readFully(it) })
    }

    override fun close() {
        closed = true
        runCatching { server?.close() }
        runCatching { socket?.close() }
        reply?.result?.completeExceptionally(IOException("蓝牙传图连接已关闭"))
        timeout.shutdownNow()
    }
}
