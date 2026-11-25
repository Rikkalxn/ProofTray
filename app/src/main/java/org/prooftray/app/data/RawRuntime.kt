package org.prooftray.app.data

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.Trace
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.prooftray.app.domain.PhotoAsset

/** Bounded source access and separate queues for embedded previews and expensive RAW development. */
internal object RawRuntime {
    private val decodeExecutor = worker("ProofTray-RAW")
    private val previewExecutor = worker("ProofTray-RAW-preview")
    private val native by lazy { System.loadLibrary("prooftray_raw") }

    private fun worker(name: String) = Executors.newSingleThreadExecutor { work -> Thread(work, name).apply { isDaemon = true } }

    suspend fun <T : Any> decode(section: String, release: (T) -> Unit, render: (CancellationSignal) -> T): T =
        run(decodeExecutor, section, release, render)

    suspend fun <T : Any> preview(read: (CancellationSignal) -> T): T = run(previewExecutor, "ProofTray.raw.preview", {}, read)

    fun <T : Any> withFile(context: Context, asset: PhotoAsset, signal: CancellationSignal, read: (Int) -> T?): T {
        require(asset.sizeBytes <= MAX_PHOTO_SOURCE_BYTES) { "RAW 文件超过 512 MiB 处理上限" }
        return checkNotNull(withSeekablePhotoFile(context, Uri.parse(asset.uri), signal, read)) { "RAW 解码器没有返回图像" }
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
}
