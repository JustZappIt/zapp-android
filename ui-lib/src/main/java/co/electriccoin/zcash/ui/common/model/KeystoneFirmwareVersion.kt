package co.electriccoin.zcash.ui.common.model

/**
 * Keystone hardware-wallet firmware version triple, as reported by the device itself in the
 * `zcash-batch-sig-result` UR envelope's dedicated firmware-version field (CBOR key 3) — see
 * `ZcashBatchSigResult::get_firmware_version()` in keystone-sdk-rust's `ur-registry` crate.
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

/**
 * Converts the raw `[major, minor, build]` bytes carried directly in the batch-sign-result UR
 * envelope (`KeystoneBatchDecodeResult.firmwareVersion`) into a [KeystoneFirmwareVersion].
 *
 * Unlike the legacy single-transaction PCZT-echo response, the compact batch protocol never
 * echoes signed PCZT bytes back (`BatchSignResponse` is signatures-only), so there is no
 * `keystone:fw_version` proprietary field to scan for on that path — the envelope's own field is
 * the only source of the firmware version for migration. Returns `null` if the byte array isn't
 * exactly 3 bytes (device didn't report a version — pre-migration-support firmware).
 */
fun ByteArray.toKeystoneFwVersion(): KeystoneFirmwareVersion? {
    if (size != 3) return null
    return KeystoneFirmwareVersion(
        major = this[0].toInt() and 0xFF,
        minor = this[1].toInt() and 0xFF,
        build = this[2].toInt() and 0xFF,
    )
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
