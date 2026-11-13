package org.openbridge.app.camera

import java.io.IOException

internal fun orderedSonyDates(dates: List<SonyCaptureDay>, direction: CatalogDirection): List<SonyCaptureDay> {
    val byDay = if (direction == CatalogDirection.OLDEST) compareBy(SonyCaptureDay::epochDay) else compareByDescending(SonyCaptureDay::epochDay)
    return dates.sortedWith(byDay.thenBy(SonyCaptureDay::slot))
}

internal class SonyContentsCatalog(private val read: (Int, LongArray) -> ByteArray, private val slots: () -> List<Int>) {
    private val files = mutableMapOf<Long, SonyMediaFile>()

    inner class Cursor internal constructor(val dates: List<SonyCaptureDay>, startAt: SonyCaptureDay?) {
        private var dateIndex = if (startAt == null) 0 else dates.indexOf(startAt).also {
            require(it >= 0) { "所选卡槽的拍摄日期已变化，请刷新目录" }
        }
        private var start = dates.getOrNull(dateIndex)?.localMillis ?: 0L
        private val seen = hashSetOf<Long>()
        val hasMore: Boolean get() = dateIndex < dates.size

        fun next(): List<Long> {
            if (!hasMore) return emptyList()
            val day = dates[dateIndex]
            val entries = page(day.slot, start, 100)
            val todays = entries.takeWhile { day.containsTime(it.localTime) }
            if (entries.size < 100 || todays.size != entries.size) {
                dateIndex++
                start = dates.getOrNull(dateIndex)?.localMillis ?: 0
            } else {
                val last = entries.last().localTime
                // Sony's page boundary is inclusive. Advance one millisecond if the entire page shares that timestamp.
                val next = if (entries.first().localTime == last) Math.addExact(last, 1) else last
                check(next > start) { "机身目录分页未推进，请刷新照片列表" }
                start = next
            }
            return buildList {
                for (entry in todays) for (file in entry.files) {
                    if (seen.add(file.ref.id)) {
                        files[file.ref.id] = file
                        add(file.ref.id)
                    }
                }
            }
        }
    }

    fun cursor(onlySlot: Int? = null, direction: CatalogDirection = CatalogDirection.NEWEST, startAt: SonyCaptureDay? = null): Cursor {
        val dates = mutableListOf<SonyCaptureDay>()
        var readable = false
        for (slot in onlySlot?.let { listOf(it) } ?: slots()) {
            val values = try { sonyDates(read(SONY_DATES, longArrayOf(slot.toLong()))) }
            catch (error: PtpResponseException) {
                if (error.response == 0x2013 || error.response == 0x201d) continue // empty/absent slot, only for GetCapturedDateList
                throw error
            }
            readable = true
            dates += values.map { SonyCaptureDay(slot, it) }
        }
        check(readable || onlySlot != null) { "相机照片目录暂不可读，请等待机身完成存储卡准备后刷新" }
        return Cursor(orderedSonyDates(dates, direction), startAt)
    }

    private fun page(slot: Int, time: Long, limit: Int): List<SonyContentEntry> {
        // The fifth parameter is isLocalTime. Both view orders page forward within the camera's local day.
        val args = longArrayOf(time and 0xffff_ffffL, time ushr 32, limit.toLong(), slot.toLong(), 1)
        return sonyContentPage(read(SONY_LIST, args), slot)
    }

    fun file(handle: Long): SonyMediaFile = files[handle] ?: throw IOException("本次连接尚未确认这张照片，请刷新导入列表")

    fun verify(handle: Long): SonyMediaFile {
        val old = file(handle)
        fun match(entries: List<SonyContentEntry>) = entries.asSequence().flatMap { it.files.asSequence() }.firstOrNull { it.ref == old.ref }
        val entries = page(old.ref.slot, old.createdLocal, 10)
        val fresh = match(entries) ?: if (entries.size == 10) match(page(old.ref.slot, old.createdLocal, 100)) else null
        check(fresh != null && fresh.media == old.media && fresh.path == old.path && fresh.size == old.size &&
            fresh.modifiedUtc == old.modifiedUtc && fresh.createdUtc == old.createdUtc) { "相机中的文件或存储卡已变化，请刷新后重新选择原片" }
        files[handle] = fresh
        return fresh
    }

}
