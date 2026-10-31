package org.openbridge.app.domain

import java.net.URI

/** Only a single device MediaStore item is eligible; camera objects, caches and provider collections are never deletion targets. */
fun isMediaItemUri(value: String): Boolean = runCatching {
    val uri = URI(value)
    if (uri.scheme != "content" || uri.authority != "media" || uri.query != null || uri.fragment != null) return@runCatching false
    if (uri.rawPath != uri.path || !uri.path.startsWith('/') || uri.path.endsWith('/') || uri.path.contains("//")) return@runCatching false
    val parts = uri.path.drop(1).split('/')
    if (parts.firstOrNull()?.matches(Regex("[A-Za-z0-9_-]+")) != true) return@runCatching false
    val id = parts.lastOrNull()?.toLongOrNull() ?: return@runCatching false
    if (id <= 0) return@runCatching false
    when (parts.size) {
        3 -> parts[1] in setOf("file", "downloads")
        4 -> parts[1] in setOf("images", "video", "audio") && parts[2] == "media"
        else -> false
    }
}.getOrDefault(false)

fun isMediaRequestItemUri(value: String): Boolean = isMediaItemUri(value) && runCatching {
    val parts = URI(value).path.drop(1).split('/')
    parts.size == 4 && parts[1] in setOf("images", "video", "audio") && parts[2] == "media"
}.getOrDefault(false)
