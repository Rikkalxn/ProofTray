package org.prooftray.app.util

import java.security.MessageDigest

/** Stable lowercase content identifiers. Callers retain their existing field ordering and UTF-8 encoding. */
internal fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
