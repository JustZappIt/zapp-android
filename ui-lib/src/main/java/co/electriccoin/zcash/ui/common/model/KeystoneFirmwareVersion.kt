package co.electriccoin.zcash.ui.common.model

/**
 * Keystone hardware-wallet firmware version triple, matching the ordering keystone3-firmware
 * stamps into every signed PCZT's `global.proprietary["keystone:fw_version"]` field.
 */
data class KeystoneFirmwareVersion(
    val major: Int,
    val minor: Int,
    val build: Int
) : Comparable<KeystoneFirmwareVersion> {
    override fun compareTo(other: KeystoneFirmwareVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return build.compareTo(other.build)
    }
}

private val FIRMWARE_VERSION_KEY = "keystone:fw_version".toByteArray(Charsets.US_ASCII)
private const val FIRMWARE_VERSION_VALUE_LENGTH = 3

/**
 * Scans a signed PCZT's raw bytes for the Keystone firmware version stamp.
 *
 * PCZT proprietary fields are postcard-encoded `BTreeMap<String, Vec<u8>>` entries: a varint key
 * length, the UTF-8 key bytes, a varint value length, then the value bytes. For the 3-byte
 * firmware version value the length byte is always `0x03`, so this looks for the ASCII key
 * literal directly in the byte stream and reads the 3 bytes immediately following the expected
 * `0x03` length byte. Returns `null` if the key isn't present (legacy firmware that predates the
 * stamping feature) or the bytes that follow don't match the expected shape.
 */
fun ByteArray.readKeystoneFwVersion(): KeystoneFirmwareVersion? {
    val keyStart = indexOfSubArray(FIRMWARE_VERSION_KEY)
    if (keyStart < 0) return null

    val lengthIndex = keyStart + FIRMWARE_VERSION_KEY.size
    if (lengthIndex >= size || this[lengthIndex] != FIRMWARE_VERSION_VALUE_LENGTH.toByte()) return null

    val valueStart = lengthIndex + 1
    if (valueStart + FIRMWARE_VERSION_VALUE_LENGTH > size) return null

    return KeystoneFirmwareVersion(
        major = this[valueStart].toInt() and 0xFF,
        minor = this[valueStart + 1].toInt() and 0xFF,
        build = this[valueStart + 2].toInt() and 0xFF,
    )
}

private fun ByteArray.indexOfSubArray(needle: ByteArray): Int {
    if (needle.isEmpty() || needle.size > size) return -1
    outer@ for (i in 0..(size - needle.size)) {
        for (j in needle.indices) {
            if (this[i + j] != needle[j]) continue@outer
        }
        return i
    }
    return -1
}

/**
 * Decides whether a Keystone-signed migration transaction may proceed to broadcast, given the
 * firmware version (if any) detected on the signed PCZT.
 */
object KeystoneFirmwarePolicy {
    enum class Outcome {
        /** Firmware reported a version and it meets [required]. */
        OK,

        /** Firmware reported a version but it's below [required]. */
        UPDATE_REQUIRED,

        /** Firmware didn't report a version at all (pre-stamp build). */
        LEGACY,
    }

    fun evaluate(
        detected: KeystoneFirmwareVersion?,
        required: KeystoneFirmwareVersion
    ): Outcome {
        if (detected == null) return Outcome.LEGACY
        return if (detected >= required) Outcome.OK else Outcome.UPDATE_REQUIRED
    }
}
