// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.hd

import java.text.Normalizer
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal actual fun platformNormalizeNfkd(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)

internal actual fun platformPbkdf2Sha512(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    outputBytes: Int,
): ByteArray {
    val chars = password.decodeToString().toCharArray()
    val spec = PBEKeySpec(chars, salt, iterations, outputBytes * Byte.SIZE_BITS)
    return try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
    } finally {
        chars.fill('\u0000')
        spec.clearPassword()
    }
}

internal actual fun platformHmacSha512(key: ByteArray, data: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(key, "HmacSHA512")) }.doFinal(data)
