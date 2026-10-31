package org.openbridge.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.OsConstants
import androidx.exifinterface.media.ExifInterface
import java.io.FileNotFoundException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openbridge.app.domain.ExportFormat
import org.openbridge.app.domain.ExportOptions
import org.openbridge.app.domain.ExportStatus
import org.openbridge.app.domain.ExportTask
import org.openbridge.app.domain.MediaFormat
import org.openbridge.app.domain.PhotoAsset
import org.openbridge.app.color.LutRepository

/** Process-local executor. Durable records make interrupted jobs explicit; this is not a foreground service. */
class BatchExportManager(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val database = CatalogDatabase(context.applicationContext)
    private val colors = LutRepository.get(context)
    private val mutableFailure = MutableStateFlow<String?>(null)
    private val mutablePaused = MutableStateFlow(false)
    private val exceptionHandler = CoroutineExceptionHandler { _, error ->
        mutablePaused.value = true
        mutableFailure.value = "导出任务已暂停：${error.message ?: "执行器发生异常"}"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val mutex = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val cancelledIds = ConcurrentHashMap.newKeySet<String>()
    private val mutableTasks = MutableStateFlow<List<ExportTask>>(emptyList())
    private var restored = false
    @Volatile private var activeTaskId: String? = null
    @Volatile private var activeJob: Job? = null

    val tasks: StateFlow<List<ExportTask>> = mutableTasks.asStateFlow()
    val paused: StateFlow<Boolean> = mutablePaused.asStateFlow()
    val failure: StateFlow<String?> = mutableFailure.asStateFlow()

    init {
        scope.launch {
            for (signal in wake) {
                while (!mutablePaused.value) {
                    val next = mutex.withLock { mutableTasks.value.firstOrNull { it.status == ExportStatus.QUEUED } } ?: break
                    activeTaskId = next.id
                    val job = scope.launch { execute(next) }
                    activeJob = job
                    if (next.id in cancelledIds) job.cancel()
                    job.join()
                    activeJob = null
                    activeTaskId = null
                }
            }
        }
    }

    suspend fun restore() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (restored) return@withLock
            val recovered = database.loadTasks().map { task ->
                if (task.status !in setOf(ExportStatus.RUNNING, ExportStatus.QUEUED)) return@map task
                val cleaned = task.outputUri?.let { deleteOutput(Uri.parse(it)) } ?: true
                task.copy(
                    status = ExportStatus.FAILED, progress = 0f, outputUri = if (cleaned) null else task.outputUri,
                    message = if (cleaned) "上次运行已中断，请重试" else "上次运行已中断，未完成副本清理失败；请先检查目标文件夹"
                )
            }
            database.putTasks(recovered)
            mutableTasks.value = recovered
            restored = true
        }
    }

    suspend fun enqueue(assets: List<PhotoAsset>, options: ExportOptions, destinationUri: String): Unit = withContext(Dispatchers.IO) {
        require(options.jpegQuality in 1..100 && options.maxEdge >= 0) { "导出参数无效" }
        options.look?.validate()
        require(options.format != ExportFormat.ORIGINAL || options.look == null || options.look.isIdentity) { "原格式复制不应用调色，请选择 JPEG 或 PNG" }
        require(DocumentsContract.isTreeUri(Uri.parse(destinationUri))) { "请选择可写入的目标文件夹" }
        restore()
        mutex.withLock {
            val added = assets.distinctBy { it.id }.map { asset ->
                ExportTask(UUID.randomUUID().toString(), asset, options, destinationUri)
            }
            val combined = mutableTasks.value + added
            database.putTasks(combined)
            mutableTasks.value = combined
        }
        wake.trySend(Unit)
        Unit
    }

    fun setPaused(value: Boolean) {
        mutablePaused.value = value
        if (!value) {
            mutableFailure.value = null
            wake.trySend(Unit)
        }
    }

    fun cancel(id: String) {
        cancelledIds += id
        if (activeTaskId == id) activeJob?.cancel()
        scope.launch {
            updateTask(id) { task ->
                if (task.status == ExportStatus.QUEUED) task.copy(status = ExportStatus.CANCELLED, message = "已取消") else task
            }
        }
    }

    fun retry(id: String) {
        scope.launch {
            mutex.withLock {
                val list = mutableTasks.value.toMutableList()
                val index = list.indexOfFirst { it.id == id }
                if (index < 0 || list[index].status !in setOf(ExportStatus.FAILED, ExportStatus.CANCELLED)) return@withLock
                val previous = list[index]
                if (previous.outputUri != null && !deleteOutput(Uri.parse(previous.outputUri))) {
                    list[index] = previous.copy(message = "无法清理上次的未完成副本，请检查目标文件夹授权后重试")
                } else {
                    cancelledIds -= id
                    val refreshed = database.assetForUri(previous.asset.uri) ?: previous.asset
                    list[index] = previous.copy(asset = refreshed, status = ExportStatus.QUEUED, progress = 0f, outputUri = null, message = null)
                }
                persistTask(list, index)
                mutableTasks.value = list
            }
            wake.trySend(Unit)
        }
    }

    fun close() {
        // Active jobs enter NonCancellable cleanup; queued jobs remain journaled for explicit recovery.
        scope.cancel()
    }

    private suspend fun execute(task: ExportTask) {
        var output: Uri? = null
        val phase = IoPhase()
        try {
            checkCancelled(task.id)
            validateConversion(task)
            updateTask(task.id) { it.copy(status = ExportStatus.RUNNING, progress = 0f, message = "准备导出") }
            phase.name = "创建目标文件"
            val treeUri = Uri.parse(task.destinationUri)
            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            val destination = DocumentsContract.createDocument(resolver, parent, outputMime(task), outputName(task)) ?: error("无法创建导出文件")
            output = destination
            // Record ownership before writing so a process restart can find and clean the incomplete file.
            updateTask(task.id) { it.copy(outputUri = destination.toString()) }
            checkCancelled(task.id)
            val message = if (task.options.format == ExportFormat.ORIGINAL) {
                copyOriginal(task, destination, phase)
                "原始文件已复制，未重新编码"
            } else {
                convertRaster(task, destination, phase)
            }
            checkCancelled(task.id)
            phase.name = "记录完成状态"
            updateTask(task.id) { it.copy(status = ExportStatus.COMPLETED, progress = 1f, message = message) }
        } catch (_: CancellationException) {
            withContext(NonCancellable) { finishFailed(task.id, output, ExportStatus.CANCELLED, "已取消") }
        } catch (error: OutOfMemoryError) {
            mutablePaused.value = true
            withContext(NonCancellable) { finishFailed(task.id, output, ExportStatus.FAILED, "可用内存不足，请减小导出尺寸") }
        } catch (error: Exception) {
            val status = if (task.id in cancelledIds) ExportStatus.CANCELLED else ExportStatus.FAILED
            withContext(NonCancellable) { finishFailed(task.id, output, status, describeFailure(phase.name, error)) }
        }
    }

    private fun validateConversion(task: ExportTask) {
        if (task.options.format == ExportFormat.ORIGINAL) return
        require(task.asset.format != MediaFormat.RAW) { "RAW 渲染尚未接入；可选择原格式导出，或交给 Lightroom 编辑" }
        require(task.asset.format in setOf(MediaFormat.JPEG, MediaFormat.PNG, MediaFormat.HEIF)) {
            "此格式暂不支持转换，可选择原格式导出"
        }
    }

    private suspend fun copyOriginal(task: ExportTask, output: Uri, phase: IoPhase) {
        var copied = 0L
        var lastUpdate = 0L
        phase.name = "读取源文件"
        resolver.openInputStream(Uri.parse(task.asset.uri))?.use { input ->
            phase.name = "打开目标文件"
            resolver.openOutputStream(output, "w")?.use { destination ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    checkCancelled(task.id)
                    phase.name = "读取源文件"
                    val count = input.read(buffer)
                    if (count == -1) break
                    phase.name = "写入目标文件"
                    destination.write(buffer, 0, count)
                    copied += count
                    val now = System.nanoTime()
                    if (now - lastUpdate >= 100_000_000L) {
                        val fraction = if (task.asset.sizeBytes > 0) copied.toDouble() / task.asset.sizeBytes else 0.0
                        val progress = fraction.toFloat().coerceIn(0f, 0.99f)
                        updateTask(task.id, persist = false) { it.copy(progress = progress, message = "正在复制原文件") }
                        lastUpdate = now
                    }
                }
                phase.name = "完成保存"
                updateTask(task.id, persist = false) { it.copy(progress = 0.99f, message = "复制结束，正在完成保存") }
                destination.flush()
            } ?: error("无法打开目标文件")
        } ?: error("源文件无法读取，可能已删除或授权失效")
        phase.name = "校验复制长度"
        require(copied > 0) { "源文件为空" }
        if (task.asset.sizeBytes > 0) require(copied == task.asset.sizeBytes) { "源文件大小已变化，请重新导入后导出" }
    }

    private suspend fun convertRaster(task: ExportTask, output: Uri, phase: IoPhase): String {
        checkCancelled(task.id)
        phase.name = "解码源图片"
        updateTask(task.id, persist = false) { it.copy(message = "正在解码图片") }
        val budget = minOf(16_000_000L, Runtime.getRuntime().maxMemory() / 12).coerceAtLeast(1_000_000L)
        val source = ImageDecoder.createSource(resolver, Uri.parse(task.asset.uri))
        // ImageDecoder applies the source EXIF orientation. Do not rotate the bitmap a second time.
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val edge = max(info.size.width, info.size.height)
            val ratio = if (task.options.maxEdge > 0) minOf(1.0, task.options.maxEdge.toDouble() / edge) else 1.0
            val width = (info.size.width * ratio).roundToInt().coerceAtLeast(1)
            val height = (info.size.height * ratio).roundToInt().coerceAtLeast(1)
            require(width.toLong() * height <= budget) { "此尺寸超过当前安全内存上限，请选择更小的导出长边（如 4096）" }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = task.options.look?.isIdentity == false
            decoder.setTargetSize(width, height)
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
        var flattened: Bitmap? = null
        var graded: Bitmap? = null
        try {
            checkCancelled(task.id)
            val look = task.options.look?.takeUnless { it.isIdentity }
            val processed = if (look != null) {
                val mutable = if (decoded.config == Bitmap.Config.ARGB_8888 && decoded.isMutable) decoded
                    else decoded.copy(Bitmap.Config.ARGB_8888, true)?.also { graded = it } ?: error("无法创建调色位图")
                if (Build.VERSION.SDK_INT >= 34) mutable.setGainmap(null)
                phase.name = "应用预设与 LUT"
                updateTask(task.id, persist = false) { it.copy(progress = 0.35f, message = "正在应用 ${look.name}") }
                colors.apply(mutable, look)
                checkCancelled(task.id)
                mutable
            } else decoded
            val bitmap = if (task.options.format == ExportFormat.JPEG && processed.hasAlpha()) {
                Bitmap.createBitmap(processed.width, processed.height, Bitmap.Config.ARGB_8888).also {
                    flattened = it
                    val canvas = Canvas(it)
                    canvas.drawColor(Color.WHITE)
                    canvas.drawBitmap(processed, 0f, 0f, null)
                }
            } else processed
            updateTask(task.id, persist = false) { it.copy(progress = 0.5f, message = "正在编码并写入图片") }
            val coroutine = currentCoroutineContext()
            phase.name = "打开目标文件"
            resolver.openOutputStream(output, "w")?.use { stream ->
                val cancellable = object : OutputStream() {
                    override fun write(value: Int) { coroutine.ensureActive(); stream.write(value) }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        coroutine.ensureActive()
                        if (task.id in cancelledIds) throw CancellationException("Cancelled")
                        stream.write(bytes, offset, length)
                    }
                    override fun flush() = stream.flush()
                }
                val format = if (task.options.format == ExportFormat.JPEG) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
                phase.name = "编码并写入图片"
                check(bitmap.compress(format, task.options.jpegQuality, cancellable)) { "图片编码失败" }
                phase.name = "完成保存"
                stream.flush()
            } ?: error("无法打开目标文件")
            checkCancelled(task.id)
            phase.name = "保存基础 EXIF"
            updateTask(task.id, persist = false) { it.copy(progress = 0.99f, message = "正在保存 EXIF 并完成导出") }
            val preserved = preserveExif(Uri.parse(task.asset.uri), output, bitmap.width, bitmap.height)
            return "已导出 ${bitmap.width} × ${bitmap.height}，sRGB" + (look?.let { " · ${it.name}" } ?: "") +
                if (preserved) "；已保留基础 EXIF" else "；未能保留 EXIF"
        } finally {
            flattened?.recycle()
            graded?.recycle()
            decoded.recycle()
        }
    }

    private fun preserveExif(source: Uri, output: Uri, width: Int, height: Int): Boolean {
        val original = try { resolver.openInputStream(source)?.use { ExifInterface(it) } } catch (_: Exception) { null } ?: return false
        val descriptor = try { resolver.openFileDescriptor(output, "rw") } catch (_: Exception) { null } ?: return false
        descriptor.use {
            val exif = ExifInterface(it.fileDescriptor)
            for (tag in EXIF_TAGS) original.getAttribute(tag)?.let { value -> exif.setAttribute(tag, value) }
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
            exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
            exif.setAttribute(ExifInterface.TAG_COLOR_SPACE, "1")
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "OpenBridge")
            // Once rewriting begins, failures invalidate this new copy and must reach the task's cleanup path.
            exif.saveAttributes()
        }
        return true
    }

    private suspend fun checkCancelled(id: String) {
        currentCoroutineContext().ensureActive()
        if (id in cancelledIds) throw CancellationException("Cancelled")
    }

    private suspend fun finishFailed(id: String, output: Uri?, status: ExportStatus, reason: String) {
        val cleaned = output?.let { deleteOutput(it) } ?: true
        updateTask(id) {
            it.copy(status = status, progress = 0f, outputUri = if (cleaned) null else output.toString(),
                message = reason + if (cleaned) "" else "；未完成副本清理失败，请检查目标文件夹")
        }
    }

    private suspend fun updateTask(id: String, persist: Boolean = true, transform: (ExportTask) -> ExportTask) {
        mutex.withLock {
            val list = mutableTasks.value.toMutableList()
            val index = list.indexOfFirst { it.id == id }
            if (index < 0) return@withLock
            list[index] = transform(list[index])
            if (persist) persistTask(list, index)
            mutableTasks.value = list
        }
    }

    private fun persistTask(list: MutableList<ExportTask>, index: Int) {
        try {
            database.putTask(list[index], index)
        } catch (error: Exception) {
            mutablePaused.value = true
            val message = "无法保存任务状态：${error.message}"
            mutableFailure.value = message
            list[index] = list[index].copy(status = ExportStatus.FAILED, message = message)
            mutableTasks.value = list
            throw error
        }
    }

    private fun deleteOutput(uri: Uri): Boolean {
        return try {
            if (DocumentsContract.deleteDocument(resolver, uri)) true else {
                resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { cursor ->
                    !cursor.moveToFirst()
                } ?: false
            }
        } catch (_: FileNotFoundException) {
            true
        } catch (_: Exception) {
            // Permission loss and unavailable providers do not prove that the file is gone.
            false
        }
    }

    private fun outputName(task: ExportTask): String {
        val stem = task.asset.name.substringBeforeLast('.', task.asset.name).replace(Regex("[\\\\/:*?\"<>|]"), "_").take(96)
        val extension = when (task.options.format) {
            ExportFormat.ORIGINAL -> task.asset.name.substringAfterLast('.', "bin").filter(Char::isLetterOrDigit).take(12).ifBlank { "bin" }
            ExportFormat.JPEG -> "jpg"
            ExportFormat.PNG -> "png"
        }
        return "${stem}_OpenBridge_${task.id.take(8)}.$extension"
    }

    private fun outputMime(task: ExportTask): String = when (task.options.format) {
        ExportFormat.JPEG -> "image/jpeg"
        ExportFormat.PNG -> "image/png"
        ExportFormat.ORIGINAL -> resolver.getType(Uri.parse(task.asset.uri)) ?: "application/octet-stream"
    }

    private fun describeFailure(phase: String, error: Exception): String {
        val chain = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
        val message = when {
            chain.any { it is SecurityException } -> "文件访问授权失效或目标不允许写入"
            chain.any { it is ErrnoException && it.errno == OsConstants.ENOSPC } -> "系统报告目标存储空间不足（ENOSPC）"
            chain.any { it is ErrnoException && it.errno == OsConstants.EDQUOT } -> "系统报告存储配额已满（EDQUOT）"
            else -> "${error.javaClass.simpleName}：${error.message ?: "文件提供方没有返回详细原因"}"
        }
        return "$phase：$message"
    }

    private class IoPhase(var name: String = "准备导出")

    private companion object {
        val EXIF_TAGS = listOf(
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT
        )
    }
}
