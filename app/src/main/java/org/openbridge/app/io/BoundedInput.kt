package org.openbridge.app.io

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Does not close the caller's stream: ZIP callers must be able to advance to the next entry. */
internal fun InputStream.readBounded(
    limit: Int, errorMessage: String = "文件超过允许大小", checkCancelled: () -> Unit = {}
): ByteArray {
    require(limit > 0)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(minOf(limit, 64 * 1024))
    while (true) {
        checkCancelled()
        val count = read(buffer)
        if (count < 0) break
        require(output.size().toLong() + count <= limit) { errorMessage }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
