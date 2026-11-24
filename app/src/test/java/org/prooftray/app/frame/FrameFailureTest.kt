package org.prooftray.app.frame

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.prooftray.app.data.RecordCodec
import org.prooftray.app.domain.ExportOptions
import org.prooftray.app.domain.ExportFormat
import org.prooftray.app.domain.ExportStatus
import org.prooftray.app.domain.ExportTask
import org.prooftray.app.domain.FrameSnapshot
import org.prooftray.app.domain.MediaFormat
import org.prooftray.app.domain.PhotoAsset

class FrameFailureTest {
    private fun task(frame: FrameSnapshot? = FrameSnapshot(FrameTemplates.all.first())) =
        ExportTask("test", PhotoAsset("photo", "content://photos/1", "test.jpg", MediaFormat.JPEG), ExportOptions(frame = frame), "")

    @Test fun initializationErrorsBecomeRecoverableAtTheFeatureBoundary() {
        assertThrows(IllegalStateException::class.java) {
            initialFrameTemplate(null) { throw ExceptionInInitializerError("ICU syntax") }
        }
        assertThrows(IllegalStateException::class.java) {
            initialFrameTemplate(null) { throw NoClassDefFoundError("Could not initialize FrameTextFormatter") }
        }
        assertThrows(OutOfMemoryError::class.java) { withFrameInitialization<Unit> { throw OutOfMemoryError("test") } }
    }

    @Test fun unframedJpegDoesNotInitializeOrFreezeAFrame() {
        val source = task(null)
        assertEquals(source, prepareFrameExport(source) { error("must not run") })
    }

    @Test fun originalCopyStillRejectsAFrameAtTheExecutorBoundary() {
        val source = task()
        val original = source.copy(options = source.options.copy(format = ExportFormat.ORIGINAL))
        val failed = prepareFrameExport(original) { error("must not freeze a frame for original copy") }
        assertEquals(ExportStatus.FAILED, failed.status)
        assertTrue(failed.message.orEmpty().contains("原件复制不应用边框"))
        val unframed = original.copy(options = original.options.copy(frame = null))
        assertEquals(unframed, prepareFrameExport(unframed) { error("must not initialize a frame") })
    }

    @Test fun oneInitializationFailureDoesNotFailTheFollowingTask() {
        val source = task()
        val failed = prepareFrameExport(source) { throw ExceptionInInitializerError("ICU syntax") }
        val next = prepareFrameExport(source.copy(id = "next")) { it }
        assertEquals(ExportStatus.FAILED, failed.status)
        assertTrue(failed.message.orEmpty().contains("边框"))
        assertEquals(ExportStatus.QUEUED, next.status)
        assertEquals(ExportStatus.FAILED, RecordCodec.decodeTask(RecordCodec.encodeTask(failed)).status)
    }

    @Test fun invalidTemplateFailureCanStillBeJournaledAndRecovered() {
        val frame = FrameSnapshot(FrameTemplates.all.first().copy(name = ""))
        val failed = prepareFrameExport(task(frame)) { it }
        assertEquals(ExportStatus.FAILED, failed.status)
        val restored = RecordCodec.decodeTask(RecordCodec.encodeTask(failed))
        assertEquals(failed.message, restored.message)
        assertNotNull(restored.options.frame?.unavailableReason)
    }

    @Test fun cancellationRemainsCancellation() {
        assertThrows(CancellationException::class.java) { prepareFrameExport(task()) { throw CancellationException("test") } }
    }
}
