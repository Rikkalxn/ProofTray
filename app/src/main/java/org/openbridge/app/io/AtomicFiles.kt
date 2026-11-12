package org.openbridge.app.io

import android.util.AtomicFile
import java.io.File
import java.io.OutputStream

/** The caller owns locking and directory creation. A failed write preserves the previous complete file. */
internal fun File.writeAtomically(write: (OutputStream) -> Unit) {
    val atomic = AtomicFile(this)
    val stream = atomic.startWrite()
    try { write(stream); atomic.finishWrite(stream) }
    catch (error: Throwable) { atomic.failWrite(stream); throw error }
}

internal fun File.writeBytesAtomically(bytes: ByteArray) = writeAtomically { it.write(bytes) }
