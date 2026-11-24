package org.prooftray.app.data

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Trace
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.prooftray.app.domain.PhotoAsset

/** Bounded source access and separate queues for embedded previews and expensive RAW development. */
internal object RawRuntime {
    private const val MAX_SOURCE_BYTES = 512L * 1024 * 1024
    private val decodeExecutor = worker("ProofTray-RAW")
    private val previewExecutor = worker("ProofTray-RAW-preview")
    private val native by lazy { System.loadLibrary("prooftray_raw") }

    private fun worker(name: String) = Executors.newSingleThreadExecutor { work -> Thread(work, name).apply { isDaemon = true } }

    suspend fun <T : Any> decode(section: String, release: (T) -> Unit, render: (CancellationSignal) -> T): T =
        run(decodeExecutor, section, release, render)

    suspend fun <T : Any> preview(read: (CancellationSignal) -> T): T = run(previewExecutor, "ProofTray.raw.preview", {}, read)

    fun <T : Any> withFile(context: Context, asset: PhotoAsset, signal: CancellationSignal, read: (Int) -> T?): T {
        require(asset.sizeBytes <= MAX_SOURCE_BYTES) { "RAW 文件超过 512 MiB 处理上限" }
        var result: T? = null
        withSeekableFile(context.applicationContext, Uri.parse(asset.uri), signal) { result = read(it.fd) }
        return checkNotNull(result) { "RAW 解码器没有返回图像" }
    }

    private suspend fun <T : Any> run(
        executor: Executor, section: String, release: (T) -> Unit, render: (CancellationSignal) -> T
    ): T =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            executor.execute {
                try {
                    signal.throwIfCanceled()
                    Trace.beginSection(section)
                    val result = try {
                        native
                        render(signal)
                    } finally { Trace.endSection() }
                    continuation.resume(result, onCancellation = { _, value, _ -> release(value) })
                } catch (error: Throwable) {
                    val failure = if (error is LinkageError) IllegalStateException("RAW 解码库未能加载，请使用完整安装包", error) else error
                    continuation.resumeWithException(failure)
                }
            }
        }

    private fun withSeekableFile(context: Context, uri: Uri, signal: CancellationSignal, decode: (ParcelFileDescriptor) -> Unit) {
        context.contentResolver.openFileDescriptor(uri, "r", signal)?.use { original ->
            val seekable = try { Os.lseek(original.fileDescriptor, 0, OsConstants.SEEK_SET); true }
            catch (error: ErrnoException) { if (error.errno != OsConstants.ESPIPE) throw error else false }
            if (seekable) {
                require(original.statSize <= MAX_SOURCE_BYTES) { "RAW 文件超过 512 MiB 处理上限" }
                decode(original)
                return
            }
            val temporary = File.createTempFile("raw-input-", ".raw", context.cacheDir)
            try {
                ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(original.fileDescriptor)).use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var total = 0L
                        while (true) {
                            signal.throwIfCanceled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_SOURCE_BYTES) { "RAW 文件超过 512 MiB 处理上限" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY).use(decode)
            } finally { temporary.delete() }
        } ?: error("无法读取 RAW 文件，请检查文件或访问授权")
    }
}
