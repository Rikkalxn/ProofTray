package org.openbridge.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.openbridge.app.domain.DownloadState
import org.openbridge.app.domain.ExportFormat
import org.openbridge.app.domain.ExportDestination
import org.openbridge.app.domain.ExportOptions
import org.openbridge.app.domain.ExportStatus
import org.openbridge.app.domain.ExportTask
import org.openbridge.app.domain.FilterSpec
import org.openbridge.app.domain.MediaFormat
import org.openbridge.app.domain.OrientationFilter
import org.openbridge.app.domain.PhotoAsset
import org.openbridge.app.domain.PickState
import org.openbridge.app.domain.SortOrder
import org.openbridge.app.domain.cameraFileKey
import org.openbridge.app.domain.cameraCaptureKey
import org.openbridge.app.color.LookCodec

internal object RecordCodec {
    fun encodeAsset(asset: PhotoAsset): JSONObject = JSONObject().apply {
        put("id", asset.id)
        put("uri", asset.uri)
        put("name", asset.name)
        put("format", asset.format.name)
        put("sizeBytes", asset.sizeBytes)
        putNullable("capturedAtMillis", asset.capturedAtMillis)
        putNullable("width", asset.width)
        putNullable("height", asset.height)
        putNullable("sourceOrientation", asset.sourceOrientation)
        put("camera", asset.camera)
        put("lens", asset.lens)
        putNullable("iso", asset.iso)
        putNullable("aperture", asset.aperture)
        putNullable("exposureSeconds", asset.exposureSeconds)
        putNullable("focalLengthMm", asset.focalLengthMm)
        put("rating", asset.rating)
        put("pickState", asset.pickState.name)
        put("downloadState", asset.downloadState.name)
        put("tags", JSONArray(asset.tags.sorted()))
        put("sourceId", asset.sourceId)
        put("folder", asset.folder)
        putNullable("burstId", asset.burstId)
        putNullable("stackId", asset.stackId)
        put("metadataComplete", asset.metadataComplete)
        putNullable("cameraFileKey", asset.cameraFileKey)
        put("inImportList", asset.inImportList)
        put("hiddenFromLibrary", asset.hiddenFromLibrary)
        putNullable("importedAtMillis", asset.importedAtMillis)
        putNullable("previewUri", asset.previewUri)
        putNullable("previewRevision", asset.previewRevision)
        putNullable("jpegCopyImportedAtMillis", asset.jpegCopyImportedAtMillis)
        putNullable("derivedFromId", asset.derivedFromId)
        putNullable("cameraCaptureKey", asset.cameraCaptureKey)
    }

    fun decodeAsset(json: JSONObject) = PhotoAsset(
        id = json.getString("id"), uri = json.getString("uri"), name = json.getString("name"),
        format = MediaFormat.valueOf(json.getString("format")), sizeBytes = json.optLong("sizeBytes", 0),
        capturedAtMillis = json.longOrNull("capturedAtMillis"), width = json.intOrNull("width"), height = json.intOrNull("height"),
        sourceOrientation = json.intOrNull("sourceOrientation")?.takeIf { it in 1..8 },
        camera = json.optString("camera", ""), lens = json.optString("lens", ""), iso = json.intOrNull("iso"),
        aperture = json.doubleOrNull("aperture"), exposureSeconds = json.doubleOrNull("exposureSeconds"),
        focalLengthMm = json.doubleOrNull("focalLengthMm"), rating = json.optInt("rating", 0),
        pickState = PickState.valueOf(json.optString("pickState", PickState.UNMARKED.name)),
        downloadState = DownloadState.valueOf(json.optString("downloadState", DownloadState.DOWNLOADED.name)),
        tags = json.stringSet("tags"), sourceId = json.optString("sourceId", "local"), folder = json.optString("folder", ""),
        burstId = json.stringOrNull("burstId"), stackId = json.stringOrNull("stackId"), metadataComplete = json.optBoolean("metadataComplete", false),
        cameraFileKey = cameraKey(json), inImportList = json.optBoolean("inImportList", isCameraSource(json.optString("sourceId", "local"))),
        hiddenFromLibrary = json.optBoolean("hiddenFromLibrary", false), importedAtMillis = json.longOrNull("importedAtMillis"),
        previewUri = json.stringOrNull("previewUri"), previewRevision = json.stringOrNull("previewRevision"),
        jpegCopyImportedAtMillis = json.longOrNull("jpegCopyImportedAtMillis"),
        derivedFromId = json.stringOrNull("derivedFromId"), cameraCaptureKey = json.stringOrNull("cameraCaptureKey") ?: cameraCaptureKey(
            json.optString("sourceId", "local"), json.optString("name"), json.longOrNull("capturedAtMillis")
        )
    )

    private fun cameraKey(json: JSONObject): String? = json.stringOrNull("cameraFileKey") ?: cameraFileKey(
        json.optString("sourceId", "local"), json.optString("folder").substringBefore("/parent:"),
        json.optString("name"), json.optLong("sizeBytes"), json.longOrNull("capturedAtMillis")
    )

    private fun isCameraSource(source: String): Boolean = source.isNotBlank() && source != "local" && !source.startsWith("document:")

    fun encodeFilter(filter: FilterSpec): JSONObject = JSONObject().apply {
        put("text", filter.text)
        put("formats", JSONArray(filter.formats.map { it.name }.sorted()))
        put("minimumRating", filter.minimumRating)
        put("maximumRating", filter.maximumRating)
        put("pickStates", JSONArray(filter.pickStates.map { it.name }.sorted()))
        put("downloadStates", JSONArray(filter.downloadStates.map { it.name }.sorted()))
        putNullable("capturedAfterMillis", filter.capturedAfterMillis)
        putNullable("capturedBeforeMillis", filter.capturedBeforeMillis)
        putNullable("minimumSizeBytes", filter.minimumSizeBytes)
        putNullable("maximumSizeBytes", filter.maximumSizeBytes)
        putNullable("minimumIso", filter.minimumIso)
        putNullable("maximumIso", filter.maximumIso)
        putNullable("minimumAperture", filter.minimumAperture)
        putNullable("maximumAperture", filter.maximumAperture)
        putNullable("minimumExposureSeconds", filter.minimumExposureSeconds)
        putNullable("maximumExposureSeconds", filter.maximumExposureSeconds)
        putNullable("minimumFocalLengthMm", filter.minimumFocalLengthMm)
        putNullable("maximumFocalLengthMm", filter.maximumFocalLengthMm)
        put("camera", filter.camera)
        put("lens", filter.lens)
        put("tags", JSONArray(filter.tags.sorted()))
        put("orientation", filter.orientation.name)
        put("pairedRawJpegOnly", filter.pairedRawJpegOnly)
        put("includeUnknownMetadata", filter.includeUnknownMetadata)
        put("sortOrder", filter.sortOrder.name)
    }

    fun decodeFilter(json: JSONObject) = FilterSpec(
        text = json.optString("text", ""), formats = json.stringSet("formats").map { MediaFormat.valueOf(it) }.toSet(),
        minimumRating = json.optInt("minimumRating", 0), maximumRating = json.optInt("maximumRating", 5),
        pickStates = json.stringSet("pickStates").map { PickState.valueOf(it) }.toSet(),
        downloadStates = json.stringSet("downloadStates").map { DownloadState.valueOf(it) }.toSet(),
        capturedAfterMillis = json.longOrNull("capturedAfterMillis"), capturedBeforeMillis = json.longOrNull("capturedBeforeMillis"),
        minimumSizeBytes = json.longOrNull("minimumSizeBytes"), maximumSizeBytes = json.longOrNull("maximumSizeBytes"),
        minimumIso = json.intOrNull("minimumIso"), maximumIso = json.intOrNull("maximumIso"),
        minimumAperture = json.doubleOrNull("minimumAperture"), maximumAperture = json.doubleOrNull("maximumAperture"),
        minimumExposureSeconds = json.doubleOrNull("minimumExposureSeconds"),
        maximumExposureSeconds = json.doubleOrNull("maximumExposureSeconds"),
        minimumFocalLengthMm = json.doubleOrNull("minimumFocalLengthMm"), maximumFocalLengthMm = json.doubleOrNull("maximumFocalLengthMm"),
        camera = json.optString("camera", ""), lens = json.optString("lens", ""), tags = json.stringSet("tags"),
        orientation = OrientationFilter.valueOf(json.optString("orientation", OrientationFilter.ANY.name)),
        pairedRawJpegOnly = json.optBoolean("pairedRawJpegOnly", false), includeUnknownMetadata = json.optBoolean("includeUnknownMetadata", true),
        sortOrder = SortOrder.valueOf(json.optString("sortOrder", SortOrder.NEWEST.name))
    )

    fun encodeTask(task: ExportTask): JSONObject = JSONObject().apply {
        put("id", task.id)
        put("asset", encodeAsset(task.asset))
        put("options", JSONObject().apply {
            put("format", task.options.format.name)
            put("jpegQuality", task.options.jpegQuality)
            put("maxEdge", task.options.maxEdge)
            put("destination", task.options.destination.name)
            put("look", task.options.look?.let(LookCodec::encode) ?: JSONObject.NULL)
            put("frame", task.options.frame?.let(org.openbridge.app.frame.FrameCodec::encodeSnapshot) ?: JSONObject.NULL)
        })
        put("destinationUri", task.destinationUri)
        put("status", task.status.name)
        put("progress", task.progress.toDouble())
        putNullable("outputUri", task.outputUri)
        putNullable("message", task.message)
    }

    fun decodeTask(json: JSONObject): ExportTask {
        val options = json.getJSONObject("options")
        return ExportTask(
            id = json.getString("id"), asset = decodeAsset(json.getJSONObject("asset")),
            options = ExportOptions(
                ExportFormat.valueOf(options.getString("format")), options.optInt("jpegQuality", 95), options.optInt("maxEdge", 0),
                options.optJSONObject("look")?.let(LookCodec::decode),
                ExportDestination.valueOf(options.optString("destination", ExportDestination.DIRECTORY.name)),
                options.optJSONObject("frame")?.let(org.openbridge.app.frame.FrameCodec::decodeSnapshot)
            ),
            destinationUri = json.getString("destinationUri"), status = ExportStatus.valueOf(json.getString("status")),
            progress = json.optDouble("progress", 0.0).toFloat(), outputUri = json.stringOrNull("outputUri"), message = json.stringOrNull("message")
        )
    }

    private fun JSONObject.putNullable(key: String, value: Any?) { put(key, value ?: JSONObject.NULL) }
    private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else getLong(key)
    private fun JSONObject.intOrNull(key: String): Int? = if (isNull(key)) null else getInt(key)
    private fun JSONObject.doubleOrNull(key: String): Double? = if (isNull(key)) null else getDouble(key)
    private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONObject.stringSet(key: String): Set<String> {
        val array = optJSONArray(key) ?: return emptySet()
        return (0 until array.length()).map { array.getString(it) }.toSet()
    }
}
