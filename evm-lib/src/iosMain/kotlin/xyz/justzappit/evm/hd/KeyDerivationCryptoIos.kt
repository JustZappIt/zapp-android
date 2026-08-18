// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.hd

import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.PBKDF2
import dev.whyoleg.cryptography.algorithms.SHA512
import kotlinx.cinterop.BetaInteropApi
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.decomposedStringWithCompatibilityMapping

@OptIn(BetaInteropApi::class)
internal actual fun platformNormalizeNfkd(value: String): String =
    NSString.create(value).decomposedStringWithCompatibilityMapping

internal actual fun platformPbkdf2Sha512(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    outputBytes: Int,
): ByteArray =
    CryptographyProvider.Default
        .get(PBKDF2)
        .secretDerivation(SHA512, iterations, outputBytes.bytes, salt)
        .deriveSecretToByteArrayBlocking(password)

internal actual fun platformHmacSha512(key: ByteArray, data: ByteArray): ByteArray =
    CryptographyProvider.Default
        .get(HMAC)
        .keyDecoder(SHA512)
        .decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, key)
        .signatureGenerator()
        .generateSignatureBlocking(data)
