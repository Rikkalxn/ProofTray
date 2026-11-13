package org.openbridge.app.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class SonyContentsCatalogTest {
    private val day = 86_400_000L
    private val first = 20_000 * day
    private val second = first + day
    private val third = second + day
    private val slots = mapOf(1 to listOf(third, first), 2 to listOf(second, third))

    private class Card(val dates: Map<Int, List<Long>>, val contents: (Int, Long) -> List<Pair<Long, Long>>) {
        val pages = mutableListOf<SonyCaptureDay>()
        private fun buffer(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

        fun read(operation: Int, args: LongArray): ByteArray {
            if (operation == SONY_DATES) {
                val days = dates.getValue(args.single().toInt())
                val out = buffer(4 + 8 * days.size).putInt(days.size)
                days.forEach { out.putLong(it) }
                return out.array()
            }
            assertEquals(SONY_LIST, operation)
            assertEquals(5, args.size)
            assertEquals(100L, args[2])
            assertEquals(1L, args[4])
            val slot = args[3].toInt()
            val time = args[0] or (args[1] shl 32)
            pages += SonyCaptureDay(slot, time)
            val entries = contents(slot, time)
            val out = buffer(148 + 200 * entries.size)
            out.position(140)
            out.putInt(slot).putInt(entries.size)
            for ((id, local) in entries) {
                out.putInt(0).putInt(id.toInt()).putInt(100).putInt(1).putInt(0).putInt(0).putInt(0)
                repeat(4) { out.putLong(local) }
                out.putInt(0).putInt(0).putInt(0).putInt(0).putInt(1)
                out.putShort(1).putShort(0)
                val path = "A:/IMG_$id.JPG".toByteArray()
                out.putInt(path.size).put(path).putInt(0x3801).putLong(100)
                out.position(out.position() + 32)
                out.putInt(0).putInt(0).putInt(0)
            }
            return out.array().copyOf(out.position())
        }
    }

    @Test fun dateOrderIsGlobalAcrossSlotsAndEqualDatesUseSlotOrder() {
        val card = Card(slots) { _, _ -> emptyList() }
        val catalog = SonyContentsCatalog(card::read) { listOf(2, 1) }
        val newest = listOf(SonyCaptureDay(1, third), SonyCaptureDay(2, third), SonyCaptureDay(2, second), SonyCaptureDay(1, first))
        val oldest = listOf(SonyCaptureDay(1, first), SonyCaptureDay(2, second), SonyCaptureDay(1, third), SonyCaptureDay(2, third))
        for ((order, expected) in listOf(CatalogDirection.NEWEST to newest, CatalogDirection.OLDEST to oldest)) {
            card.pages.clear()
            val cursor = catalog.cursor(direction = order)
            assertEquals(expected, cursor.dates)
            while (cursor.hasMore) cursor.next()
            assertEquals(expected, card.pages)
        }
    }

    @Test fun aReadableCardWithNoPhotosHasAnEmptyCatalog() {
        val card = Card(mapOf(1 to emptyList())) { _, _ -> error("No page request is needed for an empty card") }
        val cursor = SonyContentsCatalog(card::read) { listOf(1) }.cursor()
        assertTrue(cursor.dates.isEmpty())
        assertFalse(cursor.hasMore)
    }

    @Test fun jumpStartsAtTheExactDateAndSlotThenContinuesInTheChosenDirection() {
        val card = Card(slots) { _, _ -> emptyList() }
        val catalog = SonyContentsCatalog(card::read) { listOf(1, 2) }
        val start = SonyCaptureDay(2, third)
        val newest = catalog.cursor(direction = CatalogDirection.NEWEST, startAt = start)
        assertEquals(4, newest.dates.size)
        while (newest.hasMore) newest.next()
        assertEquals(listOf(start, SonyCaptureDay(2, second), SonyCaptureDay(1, first)), card.pages)
        card.pages.clear()
        val oldest = catalog.cursor(direction = CatalogDirection.OLDEST, startAt = start)
        while (oldest.hasMore) oldest.next()
        assertEquals(listOf(start), card.pages)
        assertThrows(IllegalArgumentException::class.java) { catalog.cursor(startAt = SonyCaptureDay(2, first)) }
    }

    @Test fun aFullPageWithOneTimestampAdvancesOneMillisecondWithinTheDay() {
        val stamp = first + 42
        val card = Card(mapOf(1 to listOf(first))) { _, cursor ->
            when (cursor) {
                first -> (1L..100L).map { it to stamp }
                stamp + 1 -> listOf(101L to (stamp + 1))
                else -> error("unexpected page")
            }
        }
        val catalog = SonyContentsCatalog(card::read) { listOf(1) }
        val cursor = catalog.cursor()
        assertEquals(100, cursor.next().size)
        assertTrue(cursor.hasMore)
        val last = catalog.file(cursor.next().single())
        assertEquals(101L, last.ref.content)
        assertFalse(cursor.hasMore)
        assertEquals(listOf(first, stamp + 1), card.pages.map { it.localMillis })
    }

    @Test fun inclusiveBoundaryIsDeduplicatedAndTheNextDayIsNotConsumedEarly() {
        val card = Card(mapOf(1 to listOf(first, second))) { _, cursor ->
            when (cursor) {
                first -> (1L..100L).map { it to (first + it) }
                first + 100 -> listOf(100L to (first + 100), 101L to (first + 110), 102L to second)
                second -> listOf(102L to second)
                else -> error("unexpected page")
            }
        }
        val catalog = SonyContentsCatalog(card::read) { listOf(1) }
        val cursor = catalog.cursor(direction = CatalogDirection.OLDEST)
        val ids = buildList { while (cursor.hasMore) addAll(cursor.next()) }
        assertEquals((1L..102L).toList(), ids.map { catalog.file(it).ref.content })
        assertEquals(listOf(first, first + 100, second), card.pages.map { it.localMillis })
    }
}
