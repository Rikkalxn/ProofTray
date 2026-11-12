package org.openbridge.app.camera

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Queue policy is supplied by the controller; this receiver owns only file I/O and validated checkpoints. */
internal interface CameraTransferProgress {
    fun checkCancelled()
    fun received(bytes: Long)
    suspend fun checkpoint(bytes: Long, message: String)
    suspend fun awaitResume()
}

internal class CameraFileReceiver(context: Context, private val requests: Mutex, private val active: () -> PtpLink) {
    private val context = context.applicationContext

    suspend fun receive(photo: RemotePhoto, transferSession: PtpLink, uri: Uri, checkpoint: Long, progress: CameraTransferProgress) {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "rw") ?: error("无法打开待导入文件")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { stream ->
            val partial = transferSession.supportsPartial(photo.asset.sizeBytes)
            var offset = if (partial && checkpoint in 0..minOf(stream.channel.size(), photo.asset.sizeBytes)) checkpoint else 0L
            stream.channel.truncate(offset)
            stream.channel.position(offset)
            if (!partial) {
                progress.checkpoint(0, "相机不支持分段读取；中断后会重传此文件")
                var lastProgress = 0L
                requests.withLock {
                    check(active() === transferSession) { "相机会话已更换" }
                    transferSession.original(photo.handle, stream, photo.asset.sizeBytes) { received ->
                        progress.checkCancelled()
                        val now = System.nanoTime()
                        if (now - lastProgress > 100_000_000) {
                            progress.received(received)
                            lastProgress = now
                        }
                    }
                }
                offset = stream.channel.position()
                stream.fd.sync()
            } else {
                var firstRead = true
                while (offset < photo.asset.sizeBytes) {
                    currentCoroutineContext().ensureActive()
                    progress.checkCancelled()
                    progress.awaitResume()
                    val start = offset
                    val count = minOf(transferSession.chunkSize.toLong(), photo.asset.sizeBytes - offset).toInt()
                    try {
                        val received = requests.withLock {
                            check(active() === transferSession) { "相机会话已更换" }
                            transferSession.partial(photo.handle, start, count, stream, firstRead)
                        }
                        check(received > 0 && received <= count) { "相机返回了无效的分块长度" }
                        offset += received
                        firstRead = false
                        stream.fd.sync()
                    } catch (error: Exception) { stream.channel.truncate(start); stream.channel.position(start); throw error }
                    progress.checkpoint(offset, "正在接收原片")
                }
            }
            check(offset == photo.asset.sizeBytes && stream.channel.size() == offset) { "原片长度与相机报告不一致" }
            progress.checkpoint(offset, "正在完成保存")
        }
    }
}
