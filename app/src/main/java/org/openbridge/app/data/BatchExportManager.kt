package org.openbridge.app.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openbridge.app.domain.ExportFormat
import org.openbridge.app.domain.ExportDestination
import org.openbridge.app.domain.ExportOptions
import org.openbridge.app.domain.ExportStatus
import org.openbridge.app.domain.ExportTask
import org.openbridge.app.domain.PhotoAsset
import org.openbridge.app.frame.FrameMetadataReader
import org.openbridge.app.frame.FrameRepository
import org.openbridge.app.frame.prepareFrameExport

/** Process-local executor. Durable records make interrupted jobs explicit; this is not a foreground service. */
class BatchExportManager(context: Context) {
    private val appContext = context.applicationContext
    private val outputs = ExportOutputStore(context)
    private val writer = PhotoExportWriter(context)
    private val database = CatalogDatabase.get(appContext)
    private val repository = PhotoRepository.get(appContext)
    private val catalogUpdates = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val catalogChanged = catalogUpdates.asSharedFlow()
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
        val completedOutputs = mutableListOf<ExportTask>()
        var galleryOutputs: List<Uri>? = null
        mutex.withLock {
            if (restored) return@withLock
            val recovered = database.loadTasks().map { task ->
                if (task.status !in setOf(ExportStatus.RUNNING, ExportStatus.QUEUED)) return@map task
                task.afterCleanup(outputs.clean(task.outputUri), ExportStatus.FAILED, "上次运行已中断，请重试").also {
                    if (it.status == ExportStatus.COMPLETED) completedOutputs += it
                }
            }
            database.putTasks(recovered)
            mutableTasks.value = recovered
            galleryOutputs = recovered.filter { it.status == ExportStatus.COMPLETED && it.options.destination == ExportDestination.GALLERY }
                .mapNotNull { it.outputUri?.let(Uri::parse) }
            restored = true
        }
        galleryOutputs?.let { outputsToScan -> scope.launch { outputs.restoreGalleryIndex(outputsToScan) } }
        completedOutputs.forEach { rememberOutput(it.asset, Uri.parse(checkNotNull(it.outputUri))) }
    }

    suspend fun enqueue(assets: List<PhotoAsset>, options: ExportOptions, destinationUri: String): Unit = withContext(Dispatchers.IO) {
        require(options.jpegQuality in 1..100 && options.maxEdge >= 0) { "导出参数无效" }
        options.look?.validateForExport(assets)
        require(options.format != ExportFormat.ORIGINAL || options.look == null || options.look.isIdentity) { "原格式复制不应用调色，请选择 JPEG 或 PNG" }
        if (options.destination == ExportDestination.DIRECTORY) {
            require(DocumentsContract.isTreeUri(Uri.parse(destinationUri))) { "请选择可写入的目标文件夹" }
        }
        restore()
        val frames = if (options.frame == null) null else FrameRepository(appContext)
        val added = assets.distinctBy { it.id }.map { asset ->
            currentCoroutineContext().ensureActive()
            prepareFrameExport(ExportTask(UUID.randomUUID().toString(), asset, options, destinationUri)) { frame ->
                checkNotNull(frames).snapshot(frame.template, FrameMetadataReader.read(appContext, asset))
            }.also { task ->
                if (task.status == ExportStatus.FAILED) Log.w("ProofTray.Export", task.message.orEmpty())
            }
        }
        mutex.withLock {
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
            var recovered: ExportTask? = null
            updateTask(id) { task ->
                when {
                    task.status == ExportStatus.QUEUED -> task.copy(status = ExportStatus.CANCELLED, message = "已取消")
                    task.status in setOf(ExportStatus.FAILED, ExportStatus.CANCELLED) && task.outputUri != null -> {
                        task.afterCleanup(outputs.clean(task.outputUri), ExportStatus.CANCELLED, "已取消").also {
                            if (it.status == ExportStatus.COMPLETED) recovered = it
                        }
                    }
                    else -> task
                }
            }
            recovered?.let { rememberOutput(it.asset, Uri.parse(checkNotNull(it.outputUri))) }
        }
    }

    fun retry(id: String) {
        scope.launch {
            var recovered: ExportTask? = null
            mutex.withLock {
                val list = mutableTasks.value.toMutableList()
                val index = list.indexOfFirst { it.id == id }
                if (index < 0 || list[index].status !in setOf(ExportStatus.FAILED, ExportStatus.CANCELLED)) return@withLock
                val previous = list[index]
                val output = outputs.clean(previous.outputUri)
                if (output != OutputState.ABSENT) {
                    list[index] = previous.afterCleanup(output, previous.status, "暂缓重试").also {
                        if (it.status == ExportStatus.COMPLETED) recovered = it
                    }
                } else {
                    cancelledIds -= id
                    val refreshed = repository.assetForUri(previous.asset.uri) ?: previous.asset
                    list[index] = previous.copy(asset = refreshed, status = ExportStatus.QUEUED, progress = 0f, outputUri = null, message = null)
                }
                persistTask(list, index)
                mutableTasks.value = list
            }
            recovered?.let { rememberOutput(it.asset, Uri.parse(checkNotNull(it.outputUri))) }
            wake.trySend(Unit)
        }
    }

    fun close() {
        // Active jobs enter NonCancellable cleanup; queued jobs remain journaled for explicit recovery.
        scope.cancel()
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        restore()
        mutex.withLock {
            val removed = mutableTasks.value.filter { it.canClearHistory }
            val remaining = mutableTasks.value.filterNot { it.canClearHistory }
            database.putTasks(remaining, removed.map { it.id })
            mutableTasks.value = remaining
            cancelledIds.removeAll(removed.map { it.id }.toSet())
        }
    }

    private suspend fun execute(task: ExportTask) {
        var output: Uri? = null
        val work = ExportProgress(currentCoroutineContext(), { task.id in cancelledIds }) { fraction, message ->
            updateTask(task.id, persist = false) { it.copy(progress = fraction ?: it.progress, message = message) }
        }
        try {
            checkCancelled(task.id)
            work.phase = "校验导出参数"
            writer.validate(task)
            updateTask(task.id) { it.copy(status = ExportStatus.RUNNING, progress = 0f, message = "准备导出") }
            work.phase = "创建目标文件"
            val destination = outputs.create(task)
            check(destination != Uri.parse(task.asset.uri)) { "文件提供方返回了源文件地址，已停止导出" }
            output = destination
            // Record ownership before writing so a process restart can find and clean the incomplete file.
            updateTask(task.id) { it.copy(outputUri = destination.toString()) }
            checkCancelled(task.id)
            val message = if (task.options.format == ExportFormat.ORIGINAL) {
                writer.copyOriginal(task, destination, work)
                "原始文件已复制，未重新编码"
            } else {
                writer.convertRaster(task, destination, work)
            }
            checkCancelled(task.id)
            withContext(NonCancellable) {
                work.phase = "完成相册保存"
                outputs.publish(task, destination)
                work.phase = "记录完成状态"
                updateTask(task.id) { it.copy(status = ExportStatus.COMPLETED, progress = 1f, message = message) }
                rememberOutput(task.asset, destination)
            }
        } catch (_: CancellationException) {
            withContext(NonCancellable) { finishFailed(task.id, output, ExportStatus.CANCELLED, "已取消") }
        } catch (error: OutOfMemoryError) {
            Log.e("ProofTray.Export", exportFailureContext(task, work.phase), error)
            mutablePaused.value = true
            withContext(NonCancellable) { finishFailed(task.id, output, ExportStatus.FAILED, "可用内存不足，请减小导出尺寸") }
        } catch (error: Exception) {
            Log.e("ProofTray.Export", exportFailureContext(task, work.phase), error)
            val status = if (task.id in cancelledIds) ExportStatus.CANCELLED else ExportStatus.FAILED
            withContext(NonCancellable) { finishFailed(task.id, output, status, describeFailure(work.phase, error)) }
        }
    }

    private fun exportFailureContext(task: ExportTask, phase: String): String =
        "Export failed: phase=" + phase + ", source=" + task.asset.format + ", output=" + task.options.format +
            ", frame=" + (task.options.frame != null) + ", log=" + (task.options.look?.usesLog == true)

    private suspend fun checkCancelled(id: String) {
        currentCoroutineContext().ensureActive()
        if (id in cancelledIds) throw CancellationException("Cancelled")
    }

    private suspend fun finishFailed(id: String, output: Uri?, status: ExportStatus, reason: String) {
        val result = outputs.clean(output?.toString())
        var recovered: ExportTask? = null
        updateTask(id) {
            it.copy(outputUri = output?.toString()).afterCleanup(result, status, reason).also { task ->
                if (task.status == ExportStatus.COMPLETED) recovered = task
            }
        }
        recovered?.let { rememberOutput(it.asset, Uri.parse(checkNotNull(it.outputUri))) }
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

    private suspend fun rememberOutput(source: PhotoAsset, output: Uri) {
        try {
            repository.rememberExport(source.id, output, source)
            catalogUpdates.tryEmit(Unit)
        } catch (error: Exception) { mutableFailure.value = "文件已保存，登记导出版本失败：${error.message}" }
    }

    private fun ExportTask.afterCleanup(output: OutputState, fallback: ExportStatus, reason: String): ExportTask = when (output) {
        OutputState.PUBLISHED -> copy(status = ExportStatus.COMPLETED, progress = 1f, message = "照片已保存到相册，已恢复完成记录")
        OutputState.ABSENT -> copy(status = fallback, progress = 0f, outputUri = null, message = reason)
        else -> copy(status = fallback, message = "$reason；目标文件已保留，请检查后重试")
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

}
