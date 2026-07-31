// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.p2p

/**
 * Resolves the amount of a dynamic PIX QR, whose value lives behind a bank `location` URL rather
 * than in the QR itself. [resolveAmount] returns the fiat amount string (the bank JWT's
 * `valor.original`, e.g. "89.90"), or null when the response carries no amount (the parser then
 * falls back to any static tag-54 amount). It must THROW on a transport failure — non-2xx,
 * unparseable JWT, or network error — which [PixQrParser] maps to
 * [PaymentQrError.DynamicFetchFailed]. [DirectPixResolver] is the native app implementation.
 */
fun interface DynamicPixResolver {
    suspend fun resolveAmount(locationUrl: String, orderId: String?): String?
}
