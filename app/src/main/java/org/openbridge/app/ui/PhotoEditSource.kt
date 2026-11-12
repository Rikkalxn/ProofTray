package org.openbridge.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.openbridge.app.color.LutRepository
import org.openbridge.app.data.LinearRawPreview
import org.openbridge.app.data.LogRenderer
import org.openbridge.app.data.PhotoRenderer
import org.openbridge.app.data.RawPhotoDecoder
import org.openbridge.app.domain.ColorLookSettings
import org.openbridge.app.domain.MediaFormat
import org.openbridge.app.domain.PhotoAsset
import org.openbridge.app.frame.FrameMetadataReader

internal val editRenderDispatcher = Dispatchers.Default.limitedParallelism(2)

/** A displayed or background-rendered image can outlive the producer that created it. No consumer owns its pixels exclusively. */
internal class EditBitmap(val bitmap: Bitmap) : Closeable {
    private var references = 1
    private var ownerClosed = false

    @Synchronized fun retain(): Lease {
        check(references > 0) { "预览已释放" }
        references++
        return Lease(this)
    }

    @Synchronized private fun release() {
        references--
        if (references == 0) bitmap.recycle()
    }

    @Synchronized override fun close() {
        if (!ownerClosed) { ownerClosed = true; release() }
    }

    class Lease internal constructor(private val owner: EditBitmap) : Closeable {
        val bitmap: Bitmap get() = owner.bitmap
        private var closed = false
        @Synchronized override fun close() { if (!closed) { closed = true; owner.release() } }
    }
}

internal class EditResources : Closeable {
    private val resources = mutableListOf<Closeable>()
    fun own(bitmap: Bitmap): EditBitmap = EditBitmap(bitmap).also { resources += it }
    fun hold(image: EditBitmap): EditBitmap = image.also { resources += it.retain() }
    override fun close() { resources.asReversed().forEach(Closeable::close); resources.clear() }
}

private class EditResultOwner { var resources: EditResources? = null }

/** Keep the previous published result alive until its replacement is ready; cancelled work releases only its own resources. */
@Composable
internal fun <T> rememberEditResult(initial: T, vararg keys: Any?, load: suspend EditResources.() -> T): T {
    val latest by rememberUpdatedState(load)
    val owner = remember { EditResultOwner() }
    val result by produceState(initial, *keys) {
        val resources = EditResources()
        var published = false
        try {
            val next = resources.latest()
            currentCoroutineContext().ensureActive()
            val previous = owner.resources
            value = next
            owner.resources = resources
            published = true
            previous?.close()
        } finally { if (!published) resources.close() }
    }
    DisposableEffect(owner) { onDispose { owner.resources?.close(); owner.resources = null } }
    return result
}

@Composable
internal fun rememberEditBitmap(image: EditBitmap?): Bitmap? {
    val lease = remember(image) { image?.retain() }
    DisposableEffect(lease) { onDispose { lease?.close() } }
    return lease?.bitmap
}

internal data class ColorPreviewSource(
    val image: EditBitmap? = null, val error: String? = null, val renderedLook: ColorLookSettings? = null
) {
    val bitmap: Bitmap? get() = image?.bitmap
}
internal data class LinearSource(val frame: LinearRawPreview? = null, val thumb: LinearRawPreview? = null, val error: String? = null)
internal data class PhotoEditSource(val normal: ColorPreviewSource, val linear: LinearSource, val values: Map<String, String>, val raw: Boolean)

/** One decode per requested path for the current URI/retry. Neither the selected look nor the editor tab is a decode key. */
@Composable
internal fun rememberPhotoEditSource(asset: PhotoAsset, reload: Int): PhotoEditSource = key(asset.uri, reload) {
    val context = LocalContext.current.applicationContext
    val raw = asset.format == MediaFormat.RAW
    val repository = remember(context) { LutRepository.get(context) }
    val linear = if (raw) rememberEditResult(LinearSource()) {
        try {
            val frame = LogRenderer.load(context, asset, 1600)
            LinearSource(frame, withContext(editRenderDispatcher) { frame.thumbnail(192) })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: OutOfMemoryError) { LinearSource(error = "内存不足，无法建立线性预览") }
        catch (error: Exception) { LinearSource(error = error.message ?: "线性 RAW 解码失败") }
    } else LinearSource()
    val normal = if (raw) {
        if (linear.error != null) ColorPreviewSource(error = linear.error) else linearLookPreview(linear.frame, ColorLookSettings(), repository)
    } else rememberEditResult(ColorPreviewSource()) {
        try {
            withContext(Dispatchers.IO) {
                ColorPreviewSource(own(PhotoRenderer.decode(context, asset, 1600, mutable = false)))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: OutOfMemoryError) { ColorPreviewSource(error = "可用内存不足，无法读取照片") }
        catch (error: Exception) { ColorPreviewSource(error = error.message ?: "无法读取照片") }
    }
    val values by produceState<Map<String, String>>(emptyMap()) {
        value = try { withContext(Dispatchers.IO) { FrameMetadataReader.read(context, asset) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyMap() }
    }
    PhotoEditSource(normal, linear, values, raw)
}

@Composable
internal fun linearLookPreview(frame: LinearRawPreview?, look: ColorLookSettings, repository: LutRepository): ColorPreviewSource {
    val id = look.lutId
    return key(frame, id) {
        val table by produceState<Result<ByteArray?>?>(null) {
            if (frame != null) value = withContext(Dispatchers.IO) { runCatching { id?.let(repository::canonical) } }
        }
        rememberEditResult(ColorPreviewSource(), look, table) {
            val packed = table ?: return@rememberEditResult ColorPreviewSource()
            if (frame == null) return@rememberEditResult ColorPreviewSource()
            delay(60)
            try { ColorPreviewSource(own(RawPhotoDecoder.preview(frame, packed.getOrThrow(), look)), renderedLook = look) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: OutOfMemoryError) { ColorPreviewSource(error = "RAW 预览内存不足") }
            catch (error: Exception) { ColorPreviewSource(error = error.message ?: "RAW 预览失败") }
        }
    }
}

/** The frame engine receives this graded image, never the ungraded source or an image of the composed frame. */
@Composable
internal fun rememberGradedPhoto(source: PhotoEditSource, look: ColorLookSettings, requested: Boolean): ColorPreviewSource {
    val context = LocalContext.current
    val repository = remember(context) { LutRepository.get(context) }
    if (source.raw) return when {
        source.linear.error != null -> ColorPreviewSource(error = source.linear.error)
        look.isIdentity -> source.normal.copy(renderedLook = look)
        else -> linearLookPreview(source.linear.frame, look, repository)
    }
    return rememberEditResult(ColorPreviewSource(), source.normal, look, requested) {
        if (!requested) return@rememberEditResult ColorPreviewSource()
        val image = source.normal.image ?: return@rememberEditResult ColorPreviewSource(error = source.normal.error)
        delay(60)
        try {
            if (look.isIdentity) ColorPreviewSource(hold(image), renderedLook = look)
            else withContext(editRenderDispatcher) {
                image.retain().use { lease ->
                    val copy = own(checkNotNull(lease.bitmap.copy(Bitmap.Config.ARGB_8888, true)) { "无法创建调色预览" })
                    repository.apply(copy.bitmap, look)
                    ColorPreviewSource(copy, renderedLook = look)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: OutOfMemoryError) { close(); ColorPreviewSource(error = "可用内存不足，无法建立调色预览") }
        catch (error: Exception) { close(); ColorPreviewSource(error = error.message ?: "调色失败") }
    }
}
