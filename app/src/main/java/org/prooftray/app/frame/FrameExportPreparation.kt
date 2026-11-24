package org.prooftray.app.frame

import kotlinx.coroutines.CancellationException
import org.prooftray.app.domain.ExportFormat
import org.prooftray.app.domain.ExportStatus
import org.prooftray.app.domain.ExportTask
import org.prooftray.app.domain.FrameSnapshot

/** A bad frame belongs to one task, not to the executor or the rest of the selection. */
internal fun prepareFrameExport(task: ExportTask, freeze: (FrameSnapshot) -> FrameSnapshot): ExportTask {
    val frame = task.options.frame ?: return task
    return try {
        val snapshot = withFrameInitialization {
            require(frame.unavailableReason == null) { frame.unavailableReason.orEmpty() }
            require(task.options.format != ExportFormat.ORIGINAL) { "原件复制不应用边框，请选择 JPEG 或 PNG" }
            require(task.options.format != ExportFormat.TIFF && task.options.log == null) { "Log 中间文件不应用边框" }
            FrameCodec.validate(frame.template)
            freeze(frame)
        }
        task.copy(options = task.options.copy(frame = snapshot))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        task.copy(status = ExportStatus.FAILED, message = "边框校验失败：" + (error.message ?: "模板无法使用"))
    }
}
