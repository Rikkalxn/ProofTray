package org.openbridge.app.data

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

internal enum class RawPreviewColor { DECODER, ADOBE_RGB, UNSUPPORTED }

internal fun rawPreviewColor(cameraSpace: Int, hasIcc: Boolean, decoderIsSrgb: Boolean, decoderIsSdrRgb: Boolean): RawPreviewColor = when {
    !decoderIsSdrRgb -> RawPreviewColor.UNSUPPORTED
    hasIcc || !decoderIsSrgb || cameraSpace == 1 -> RawPreviewColor.DECODER
    cameraSpace == 2 -> RawPreviewColor.ADOBE_RGB
    else -> RawPreviewColor.UNSUPPORTED
}

/** Only inspect JPEG metadata segments. Null means malformed/unknown, not permission to assume an untagged colour space. */
internal fun jpegHasIccProfile(stream: InputStream): Boolean? {
    val input = DataInputStream(stream)
    try {
        if (input.readUnsignedShort() != 0xffd8) return null
        while (true) {
            if (input.readUnsignedByte() != 0xff) return null
            var marker = input.readUnsignedByte()
            while (marker == 0xff) marker = input.readUnsignedByte()
            if (marker == 0xda || marker == 0xd9) return false
            if (marker == 0 || marker in 0xd0..0xd8) return null
            var length = input.readUnsignedShort() - 2
            if (length < 0) return null
            if (marker == 0xe2 && length >= 14) {
                val signature = ByteArray(12)
                input.readFully(signature)
                if (signature.contentEquals("ICC_PROFILE\u0000".toByteArray(Charsets.US_ASCII))) return true
                length -= signature.size
            }
            if (input.skipBytes(length) != length) return null
        }
    } catch (_: EOFException) { return null }
}
