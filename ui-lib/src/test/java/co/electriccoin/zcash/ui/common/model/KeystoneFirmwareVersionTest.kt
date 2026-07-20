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
    fun readsStampWhenPresent() {
        val bytes = pcztBytesWithStamp(major = 3, minor = 0, build = 2)

        assertEquals(KeystoneFirmwareVersion(3, 0, 2), bytes.readKeystoneFwVersion())
    }

    @Test
    fun readsStampWhenNotAtStartOfArray() {
        val bytes = byteArrayOf(0x0A, 0x0B, 0x0C) + pcztBytesWithStamp(major = 12, minor = 4, build = 1)

        assertEquals(KeystoneFirmwareVersion(12, 4, 1), bytes.readKeystoneFwVersion())
    }

    @Test
    fun returnsNullWhenKeyAbsent() {
        val bytes = "no proprietary fields here".toByteArray(Charsets.US_ASCII)

        assertNull(bytes.readKeystoneFwVersion())
    }

    @Test
    fun returnsNullWhenLengthByteIsNotThree() {
        val key = "keystone:fw_version".toByteArray(Charsets.US_ASCII)
        val bytes = key + byteArrayOf(0x04, 1, 2, 3, 4)

        assertNull(bytes.readKeystoneFwVersion())
    }

    @Test
    fun returnsNullWhenArrayTruncatedRightAfterKey() {
        val key = "keystone:fw_version".toByteArray(Charsets.US_ASCII)
        val bytes = key + byteArrayOf(0x03, 1)

        assertNull(bytes.readKeystoneFwVersion())
    }

    @Test
    fun returnsNullOnEmptyArray() {
        assertNull(ByteArray(0).readKeystoneFwVersion())
    }

    private fun pcztBytesWithStamp(
        major: Int,
        minor: Int,
        build: Int
    ): ByteArray {
        val key = "keystone:fw_version".toByteArray(Charsets.US_ASCII)
        return byteArrayOf(0x01, 0x02) + key + byteArrayOf(0x03, major.toByte(), minor.toByte(), build.toByte()) +
            byteArrayOf(0x09, 0x08)
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
