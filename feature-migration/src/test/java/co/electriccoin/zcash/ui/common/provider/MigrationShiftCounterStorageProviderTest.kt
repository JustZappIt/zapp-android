package co.electriccoin.zcash.ui.common.provider

import kotlin.test.Test
import kotlin.test.assertEquals

class MigrationShiftCounterStorageProviderTest {
    @Test
    fun `count increments only for same transfer with a completed sync since last shift`() {
        assertEquals(2, nextShiftCount("t1", 1, "t1", syncCompletedSinceLastShift = true))
        assertEquals(1, nextShiftCount("t1", 1, "t1", syncCompletedSinceLastShift = false))
        assertEquals(1, nextShiftCount("t1", 3, "t2", syncCompletedSinceLastShift = true))
    }

    @Test
    fun `new transfer without sync yields zero count`() {
        assertEquals(0, nextShiftCount("t1", 3, "t2", syncCompletedSinceLastShift = false))
    }

    @Test
    fun `null previous transfer id is treated as different transfer`() {
        assertEquals(1, nextShiftCount(null, 0, "t1", syncCompletedSinceLastShift = true))
        assertEquals(0, nextShiftCount(null, 0, "t1", syncCompletedSinceLastShift = false))
    }

    @Test
    fun `parseStoredShiftEntry handles transferId with pipes`() {
        val parsed = parseStoredShiftEntry("ab|cd|2|100")
        assertEquals("ab|cd", parsed.transferId)
        assertEquals(2, parsed.count)
        assertEquals(100L, parsed.epochSeconds)
    }

    @Test
    fun `parseStoredShiftEntry handles malformed input`() {
        val emptyParsed = parseStoredShiftEntry("")
        assertEquals(null, emptyParsed.transferId)
        assertEquals(0, emptyParsed.count)
        assertEquals(null, emptyParsed.epochSeconds)

        val malformedParsed = parseStoredShiftEntry("x")
        assertEquals(null, malformedParsed.transferId)
        assertEquals(0, malformedParsed.count)
        assertEquals(null, malformedParsed.epochSeconds)
    }
}
