package org.openbridge.app.camera

import java.io.IOException

/** Only the release acknowledgement may fail. The file's data transactions and total byte count have already succeeded. */
internal fun finishSonyPayload(received: Long, expected: Long, release: () -> Unit): IOException? {
    check(expected > 0 && received == expected) { "原片尚未完整接收，不能结束传输" }
    return try { release(); null } catch (error: IOException) { error }
}
