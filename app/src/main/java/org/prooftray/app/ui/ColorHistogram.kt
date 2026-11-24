package org.prooftray.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import org.prooftray.app.color.LutRepository
import org.prooftray.app.color.PreviewColorTable
import org.prooftray.app.domain.ColorLookSettings

private const val HISTOGRAM_BINS = 256
internal val histogramColors = listOf(Color(0xFFE57373), Color(0xFF66BB6A), Color(0xFF64B5F6))

internal class RgbHistogram(private val bins: IntArray) {
    fun densities(channel: Int, edges: List<Int>): FloatArray = FloatArray(edges.lastIndex) { column ->
        val first = edges[column]
        val end = edges[column + 1]
        var count = 0
        for (level in first until end) count += bins[channel * HISTOGRAM_BINS + level]
        count.toFloat() / (end - first)
    }
}

@Composable
internal fun rememberColorHistogram(image: EditBitmap?, repository: LutRepository, look: ColorLookSettings? = null,
    table: PreviewColorTable? = null, viewport: IntSize = IntSize.Zero): RgbHistogram? {
    if (image == null) return null
    val latest by rememberUpdatedState(look to table)
    val histogram by produceState<RgbHistogram?>(null, image, repository, viewport) {
        snapshotFlow { latest }.conflate().collect { (recipe, previewTable) ->
            value = try {
                image.retain().use { lease ->
                    withContext(editRenderDispatcher) {
                        RgbHistogram(repository.histogram(lease.bitmap, recipe, previewTable, viewport.width, viewport.height))
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: OutOfMemoryError) { null }
            catch (_: Exception) { null }
        }
    }
    return histogram
}

@Composable
internal fun rememberCurveInputHistogram(photos: PhotoEditSource, look: ColorLookSettings, singleChannel: Boolean,
    repository: LutRepository, textureViewport: IntSize): RgbHistogram? {
    val input = remember(look, singleChannel) { look.curveInputLook(singleChannel) }
    return key(input) {
        when {
            input.isIdentity -> rememberColorHistogram(photos.normal.image, repository, viewport = textureViewport)
            photos.raw -> {
                val samples = linearLookPreview(photos.linear.frame, input, repository, histogramSamples = true)
                rememberColorHistogram(samples.image, repository)
            }
            else -> rememberColorHistogram(photos.normal.image, repository, input, viewport = textureViewport)
        }
    }
}

/** Channel zero overlays RGB, and channels 1–3 select red, green or blue as in the curve editor. */
internal fun Modifier.drawHistogram(histogram: RgbHistogram?, channel: Int = 0): Modifier {
    if (histogram == null) return this
    return drawWithCache {
        val channels = if (channel == 0) 0..2 else (channel - 1)..(channel - 1)
        val maxColumns = (size.width / 1.5.dp.toPx()).toInt().coerceIn(1, HISTOGRAM_BINS)
        val levelsPerColumn = HISTOGRAM_BINS / Integer.highestOneBit(maxColumns)
        val edges = (0..HISTOGRAM_BINS step levelsPerColumn).toMutableList()
        if (levelsPerColumn > 1) {
            // Keep the black and white endpoints visible even on the compact preview.
            edges.add(1, 1)
            edges.add(edges.lastIndex, HISTOGRAM_BINS - 1)
        }
        val densities = channels.map { histogram.densities(it, edges) }
        val peak = densities.maxOf { it.max() }
        if (peak == 0f) return@drawWithCache onDrawBehind {}
        val binWidth = size.width / HISTOGRAM_BINS
        val paths = densities.map { heights ->
            Path().apply {
                moveTo(0f, size.height)
                for (i in heights.indices) {
                    // A shared square-root scale keeps sparse tones visible alongside clipping spikes.
                    val y = size.height * (1f - sqrt(heights[i] / peak))
                    lineTo(edges[i] * binWidth, y)
                    lineTo(edges[i + 1] * binWidth, y)
                }
                lineTo(size.width, size.height)
                close()
            }
        }
        val outline = Stroke(0.6.dp.toPx())
        onDrawBehind {
            channels.forEachIndexed { index, c ->
                val color = histogramColors[c]
                drawPath(paths[index], color.copy(alpha = .30f))
                drawPath(paths[index], color.copy(alpha = .65f), style = outline)
            }
        }
    }
}

@Composable
internal fun PreviewHistogram(histogram: RgbHistogram, modifier: Modifier = Modifier) {
    val plot = remember(histogram) { Modifier.drawHistogram(histogram) }
    Spacer(modifier.size(124.dp, 54.dp).clip(MaterialTheme.shapes.small).background(Color.Black.copy(alpha = .7f))
        .semantics { contentDescription = "预览 RGB 直方图" }.padding(5.dp).then(plot))
}
