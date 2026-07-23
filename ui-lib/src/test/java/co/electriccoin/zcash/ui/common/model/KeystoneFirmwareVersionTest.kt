package co.electriccoin.zcash.ui.common.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeystoneFirmwareVersionTest {
    @Test
    fun comparableOrdersByMajorThenMinorThenBuild() {
        assertTrue(KeystoneFirmwareVersion(3, 0, 2) > KeystoneFirmwareVersion(2, 9, 9))
        assertTrue(KeystoneFirmwareVersion(3, 1, 0) > KeystoneFirmwareVersion(3, 0, 2))
        assertTrue(KeystoneFirmwareVersion(3, 0, 3) > KeystoneFirmwareVersion(3, 0, 2))
        assertEquals(KeystoneFirmwareVersion(3, 0, 2), KeystoneFirmwareVersion(3, 0, 2))
    }

    @Test
    fun convertsThreeByteArray() {
        val bytes = byteArrayOf(3, 0, 2)

        assertEquals(KeystoneFirmwareVersion(3, 0, 2), bytes.toKeystoneFwVersion())
    }

    @Test
    fun convertsUnsignedByteValues() {
        val bytes = byteArrayOf(12, 4, 0xFF.toByte())

        assertEquals(KeystoneFirmwareVersion(12, 4, 255), bytes.toKeystoneFwVersion())
    }

    @Test
    fun returnsNullWhenNotThreeBytes() {
        assertNull(ByteArray(0).toKeystoneFwVersion())
        assertNull(byteArrayOf(1, 2).toKeystoneFwVersion())
        assertNull(byteArrayOf(1, 2, 3, 4).toKeystoneFwVersion())
    }

    @Test
    fun evaluateReturnsOkWhenDetectedMeetsRequired() {
        val required = KeystoneFirmwareVersion(3, 0, 2)

        assertEquals(
            KeystoneFirmwarePolicy.Outcome.OK,
            KeystoneFirmwarePolicy.evaluate(detected = KeystoneFirmwareVersion(3, 0, 2), required = required)
        )
        assertEquals(
            KeystoneFirmwarePolicy.Outcome.OK,
            KeystoneFirmwarePolicy.evaluate(detected = KeystoneFirmwareVersion(3, 1, 0), required = required)
        )
    }

    @Test
    fun evaluateReturnsUpdateRequiredWhenDetectedBelowRequired() {
        val required = KeystoneFirmwareVersion(3, 0, 2)

        assertEquals(
            KeystoneFirmwarePolicy.Outcome.UPDATE_REQUIRED,
            KeystoneFirmwarePolicy.evaluate(detected = KeystoneFirmwareVersion(3, 0, 1), required = required)
        )
        assertEquals(
            KeystoneFirmwarePolicy.Outcome.UPDATE_REQUIRED,
            KeystoneFirmwarePolicy.evaluate(detected = KeystoneFirmwareVersion(2, 9, 9), required = required)
        )
    }

    @Test
    fun evaluateReturnsLegacyWhenDetectedIsNullRegardlessOfRequired() {
        assertEquals(
            KeystoneFirmwarePolicy.Outcome.LEGACY,
            KeystoneFirmwarePolicy.evaluate(detected = null, required = KeystoneFirmwareVersion(3, 0, 2))
        )
        assertEquals(
            KeystoneFirmwarePolicy.Outcome.LEGACY,
            KeystoneFirmwarePolicy.evaluate(detected = null, required = KeystoneFirmwareVersion(0, 0, 0))
        )
    }
}
