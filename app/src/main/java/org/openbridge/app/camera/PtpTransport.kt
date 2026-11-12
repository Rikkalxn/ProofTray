package org.openbridge.app.camera

import android.net.Network
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.Closeable
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import okio.AsyncTimeout
import okio.buffer
import okio.source

internal class PtpPipe(input: InputStream, val output: OutputStream, val listenForEvents: () -> Unit) {
    val input = DataInputStream(input)
}

/** Both PTP channels stay on the Android network the user selected, including the physical socket carrying SSH. */
internal class PtpTransport(
    private val network: Network?, private val host: String, private val port: Int, private val access: SonyAccess?
) : Closeable {
    private val sockets = mutableListOf<Socket>()
    private var closed = false
    private var ssh: Session? = null

    fun open(): PtpPipe {
        if (access == null) {
            val socket = dial(port)
            return PtpPipe(socket.getInputStream(), socket.getOutputStream()) { socket.soTimeout = 0 }
        }
        val session = ssh ?: authenticate(access)
        val channel = session.getStreamForwarder("localhost", port)
        val timeout = object : AsyncTimeout() { override fun timedOut() { this@PtpTransport.close() } }
        timeout.timeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        val input = timeout.source(channel.inputStream.source()).buffer().inputStream()
        val output = channel.outputStream
        channel.connect(TIMEOUT_MS)
        return PtpPipe(input, output) { timeout.clearTimeout() }
    }

    private fun dial(destination: Int): Socket {
        val socket = synchronized(this) {
            check(!closed) { "相机连接已取消" }
            (network?.socketFactory?.createSocket() ?: Socket()).also(sockets::add)
        }
        val address = network?.getAllByName(host)?.firstOrNull()
        socket.soTimeout = TIMEOUT_MS
        socket.tcpNoDelay = true
        socket.connect(if (address == null) InetSocketAddress(host, destination) else InetSocketAddress(address, destination), TIMEOUT_MS)
        return socket
    }

    private fun authenticate(credentials: SonyAccess): Session {
        val client = JSch()
        client.hostKeyRepository = object : HostKeyRepository {
            override fun check(name: String, key: ByteArray): Int =
                if (name == host && credentials.accepts(key)) HostKeyRepository.OK else HostKeyRepository.CHANGED
            override fun add(key: HostKey, ui: UserInfo?) = Unit
            override fun remove(host: String, type: String?) = Unit
            override fun remove(host: String, type: String?, key: ByteArray?) = Unit
            override fun getKnownHostsRepositoryID() = "camera"
            override fun getHostKey(): Array<HostKey> = emptyArray()
            override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
        }
        val session = client.getSession(credentials.user, host, 22)
        synchronized(this) { check(!closed) { "相机连接已取消" }; ssh = session }
        session.setSocketFactory(object : SocketFactory {
            override fun createSocket(name: String, port: Int): Socket = dial(port)
            override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
            override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
        })
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        session.setConfig("max_input_buffer_size", "262144")
        session.setPassword(credentials.password)
        session.userInfo = object : UserInfo, UIKeyboardInteractive {
            override fun getPassphrase(): String? = null
            override fun getPassword(): String = credentials.password
            override fun promptPassword(message: String?) = false
            override fun promptPassphrase(message: String?) = false
            override fun promptYesNo(message: String?) = false
            override fun showMessage(message: String?) = Unit
            override fun promptKeyboardInteractive(destination: String?, name: String?, instruction: String?,
                                                   prompt: Array<out String>, echo: BooleanArray): Array<String>? =
                if (prompt.size == 1 && echo.contentEquals(booleanArrayOf(false))) arrayOf(credentials.password) else null
        }
        try { session.connect(TIMEOUT_MS) }
        catch (failure: com.jcraft.jsch.JSchException) {
            throw java.io.IOException("索尼访问认证失败，请核对机身指纹、配对和访问认证信息", failure)
        }
        return session
    }

    override fun close() {
        val session = synchronized(this) {
            if (closed) return
            closed = true
            sockets.forEach { runCatching { it.close() } }
            sockets.clear()
            ssh.also { ssh = null }
        }
        session?.disconnect()
    }

    private companion object { const val TIMEOUT_MS = 15_000 }
}
