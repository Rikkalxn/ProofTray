package org.openbridge.app.domain

import java.util.Locale

/** Metadata-only JNI adapter. Filtering, sorting and capture grouping live in the portable C++ core. */
object CatalogEngine {
    private const val TEXT_STRIDE = 7
    private const val INTEGER_STRIDE = 12
    private const val DECIMAL_STRIDE = 3
    private const val MAX_ASSETS = 500_000
    private val loadNative by lazy { System.loadLibrary("openbridge") }

    fun query(assets: List<PhotoAsset>, filter: FilterSpec, collapseBursts: Boolean = true): CatalogResult {
        require(assets.size <= MAX_ASSETS) { "照片数量超过单次目录查询上限" }
        if (assets.isEmpty()) return CatalogResult(emptyList(), 0, 0)
        loadNative
        val text = Array(assets.size * TEXT_STRIDE) { "" }
        val integers = LongArray(assets.size * INTEGER_STRIDE)
        val decimals = DoubleArray(assets.size * DECIMAL_STRIDE)
        val tags = mutableListOf<String>()
        assets.forEachIndexed { index, asset ->
            val textBase = index * TEXT_STRIDE
            text[textBase] = asset.id
            text[textBase + 1] = asset.name.lowercase(Locale.ROOT)
            text[textBase + 2] = asset.camera.lowercase(Locale.ROOT)
            text[textBase + 3] = asset.lens.lowercase(Locale.ROOT)
            text[textBase + 4] = asset.sourceId
            text[textBase + 5] = asset.folder
            text[textBase + 6] = asset.burstId.orEmpty()
            val numberBase = index * INTEGER_STRIDE
            integers[numberBase] = asset.format.ordinal.toLong()
            integers[numberBase + 1] = asset.sizeBytes.takeIf { it > 0 } ?: Long.MIN_VALUE
            integers[numberBase + 2] = asset.capturedAtMillis ?: Long.MIN_VALUE
            integers[numberBase + 3] = asset.width?.takeIf { it > 0 }?.toLong() ?: Long.MIN_VALUE
            integers[numberBase + 4] = asset.height?.takeIf { it > 0 }?.toLong() ?: Long.MIN_VALUE
            integers[numberBase + 5] = asset.iso?.takeIf { it > 0 }?.toLong() ?: Long.MIN_VALUE
            integers[numberBase + 6] = asset.rating.toLong()
            integers[numberBase + 7] = asset.pickState.ordinal.toLong()
            integers[numberBase + 8] = asset.downloadState.ordinal.toLong()
            integers[numberBase + 9] = tags.size.toLong()
            integers[numberBase + 10] = asset.tags.size.toLong()
            integers[numberBase + 11] = if (asset.metadataComplete) 1 else 0
            tags.addAll(asset.tags.map { it.lowercase(Locale.ROOT) })
            val decimalBase = index * DECIMAL_STRIDE
            decimals[decimalBase] = positiveOrUnknown(asset.aperture)
            decimals[decimalBase + 1] = positiveOrUnknown(asset.exposureSeconds)
            decimals[decimalBase + 2] = positiveOrUnknown(asset.focalLengthMm)
        }
        val filterText = arrayOf(filter.text, filter.camera, filter.lens).map { it.trim().lowercase(Locale.ROOT) }.toTypedArray()
        val filterIntegers = longArrayOf(
            mask(filter.formats), filter.minimumRating.toLong(), filter.maximumRating.toLong(), mask(filter.pickStates),
            mask(filter.downloadStates), filter.capturedAfterMillis ?: Long.MIN_VALUE, filter.capturedBeforeMillis ?: Long.MIN_VALUE,
            filter.minimumSizeBytes ?: Long.MIN_VALUE, filter.maximumSizeBytes ?: Long.MIN_VALUE,
            filter.minimumIso?.toLong() ?: Long.MIN_VALUE, filter.maximumIso?.toLong() ?: Long.MIN_VALUE,
            filter.orientation.ordinal.toLong(), if (filter.pairedRawJpegOnly) 1 else 0,
            if (filter.includeUnknownMetadata) 1 else 0, filter.sortOrder.ordinal.toLong()
        )
        val filterDecimals = doubleArrayOf(
            filter.minimumAperture ?: Double.NaN, filter.maximumAperture ?: Double.NaN,
            filter.minimumExposureSeconds ?: Double.NaN, filter.maximumExposureSeconds ?: Double.NaN,
            filter.minimumFocalLengthMm ?: Double.NaN, filter.maximumFocalLengthMm ?: Double.NaN
        )
        val filterTags = filter.tags.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.distinct().toTypedArray()
        val encoded = queryNative(
            text, integers, decimals, tags.toTypedArray(), filterText, filterIntegers, filterDecimals, filterTags, collapseBursts
        )
        return decode(assets, encoded)
    }

    private fun positiveOrUnknown(value: Double?): Double = value?.takeIf { it.isFinite() && it > 0.0 } ?: Double.NaN

    private fun <T : Enum<T>> mask(values: Set<T>): Long = values.fold(0L) { result, value -> result or (1L shl value.ordinal) }

    private fun decode(assets: List<PhotoAsset>, encoded: IntArray): CatalogResult {
        check(encoded.size >= 4 && encoded[0] == 1) { "Native catalog returned an unsupported response" }
        val matchedCount = encoded[1]
        val pendingCount = encoded[2]
        val groupCount = encoded[3]
        check(matchedCount in 0..assets.size && pendingCount in 0..assets.size && groupCount in 0..assets.size)
        check(matchedCount.toLong() + pendingCount <= assets.size)
        val seen = BooleanArray(assets.size)
        var offset = 4
        val groups = ArrayList<PhotoGroup>(groupCount)
        repeat(groupCount) {
            check(offset + 3 <= encoded.size) { "Truncated native group header" }
            val anchor = encoded[offset++]
            val inferred = encoded[offset++]
            val count = encoded[offset++]
            check(anchor in assets.indices && inferred in 0..1 && count > 0 && count <= encoded.size - offset)
            val members = ArrayList<PhotoAsset>(count)
            repeat(count) {
                val index = encoded[offset++]
                check(index in assets.indices && !seen[index]) { "Invalid or duplicated native group member" }
                seen[index] = true
                members.add(assets[index])
            }
            groups.add(PhotoGroup("capture:${assets[anchor].id}", members, inferred == 1))
        }
        check(offset == encoded.size) { "Trailing native catalog response data" }
        return CatalogResult(groups, matchedCount, pendingCount)
    }

    // Wire version 1: text[id,name,camera,lens,source,folder,burst]; integers
    // [format,size,time,width,height,ISO,rating,pick,download,tagOffset,tagCount,metadataComplete];
    // decimals[aperture,exposureSeconds,focalLength]. Long.MIN_VALUE / NaN mean unknown.
    // Result: [version,matched,pending,groupCount, {anchor,inferred,count,memberIndices...}...].
    private external fun queryNative(
        text: Array<String>, integers: LongArray, decimals: DoubleArray, tags: Array<String>,
        filterText: Array<String>, filterIntegers: LongArray, filterDecimals: DoubleArray, filterTags: Array<String>, collapseBursts: Boolean
    ): IntArray
}
