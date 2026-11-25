package org.prooftray.app.data

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.File

internal const val MAX_PHOTO_SOURCE_BYTES = 512L * 1024 * 1024

/** Native readers borrow a whole file. Providers can also return a pipe or a slice of a larger file. */
internal fun <T> withSeekablePhotoFile(context: Context, uri: Uri, signal: CancellationSignal? = null, read: (Int) -> T): T {
    val asset = context.contentResolver.openAssetFileDescriptor(uri, "r", signal) ?: error("无法打开照片，请检查访问权限")
    return asset.use {
        val source = it.parcelFileDescriptor
        val length = it.declaredLength
        val limitMessage = "照片超过 512 MiB 处理上限"
        require(length <= MAX_PHOTO_SOURCE_BYTES) { limitMessage }
        val statSize = source.statSize
        if (it.startOffset == 0L && statSize > 0 && (length < 0 || length == statSize)) {
            require(statSize <= MAX_PHOTO_SOURCE_BYTES) { limitMessage }
            Os.lseek(source.fileDescriptor, 0, OsConstants.SEEK_SET)
            return@use read(source.fd)
        }
        val temporary = File.createTempFile("photo-input-", ".tmp", context.cacheDir)
        try {
            it.createInputStream().use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var total = 0L
                    while (true) {
                        signal?.throwIfCanceled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_PHOTO_SOURCE_BYTES) { limitMessage }
                        output.write(buffer, 0, count)
                    }
                }
            }
            ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY).use { copied -> read(copied.fd) }
        } finally { temporary.delete() }
    }
}
