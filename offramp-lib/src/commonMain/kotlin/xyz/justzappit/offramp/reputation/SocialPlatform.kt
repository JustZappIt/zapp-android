// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.reputation

/**
 * The six accounts a user can prove control of to earn reputation, and the three identifiers each
 * one needs to travel the whole path: Reclaim's provider (which TLS session to run), the
 * ReputationManager's own name for it (what `socialVerify` is told it is verifying), and the slot
 * the contract reports it back in.
 *
 * Passport is deliberately absent. It occupies index 5 of the on-chain flags and nothing else
 * here — Zapp never mints a passport verification — but the gap it leaves is why
 * [socialVerifiedIndex] is written out per platform rather than taken from `ordinal`.
 */
enum class SocialPlatform(
    /** Reclaim's provider id: which scripted TLS session the Verifier app runs. */
    val providerId: String,
    /** `_socialName` as `socialVerify` expects it. **Case-sensitive on chain.** */
    val onChainName: String,
    /** Zero-arg getter on the RM returning this platform's reputation award, in whole RP. */
    val rpGetterSignature: String,
    /** This platform's position in the 7-tuple `socialVerified(address)` returns. */
    val socialVerifiedIndex: Int,
    /**
     * True when the provider requires an account roughly a year old. A younger account comes back
     * as a *successful* proof carrying empty `publicData` rather than as an error, so the age rule
     * is stated in the row before the user spends five minutes discovering it.
     */
    val requiresMatureAccount: Boolean = false,
) {
    // Ordered by award, descending: LinkedIn is worth double and leads the list everywhere.
    LinkedIn("6a86edbe-a0fe-420b-8db2-3155220cc949", "LinkedIn", "linkedInRp()", LINKEDIN_FLAG),
    X("aad95818-f726-4a34-be97-8d1f47631b03", "X", "xRp()", X_FLAG, requiresMatureAccount = true),
    GitHub(
        "033f0c06-2eb3-48c8-894c-5599c3356d1c",
        "GitHub",
        "gitHubRp()",
        GITHUB_FLAG,
        requiresMatureAccount = true,
    ),
    Instagram(
        "7e5b59a9-56c5-490c-a169-82a443f9b507",
        "Instagram",
        "instagramRp()",
        INSTAGRAM_FLAG,
        requiresMatureAccount = true,
    ),
    Facebook("2701510b-c835-4820-84f0-d9e74569656b", "Facebook", "facebookRp()", FACEBOOK_FLAG),

    /** Index 6, not 5 — index 5 is passport, which Zapp never mints. */
    Binance("7e40c007-f432-4d47-ac00-3e0762f8a7a0", "Binance", "binanceRp()", BINANCE_FLAG),
    ;

    companion object {
        /** Words in the `socialVerified(address)` return, passport included. */
        const val SOCIAL_VERIFIED_FLAGS = 7

        /**
         * Index 5 of the flags tuple. Zapp never mints a passport verification, so nothing reads
         * it — it is named because the gap it leaves between Facebook and Binance is exactly what
         * makes reading the tuple positionally wrong.
         */
        const val PASSPORT_FLAG_INDEX = PASSPORT_FLAG
    }
}

// The `socialVerified(address)` tuple, in the contract's order — which is not this enum's, and not
// alphabetical either. Written out because passport sits between Facebook and Binance, and reading
// the tuple positionally reports the wrong platform as verified.
private const val LINKEDIN_FLAG = 0
private const val GITHUB_FLAG = 1
private const val X_FLAG = 2
private const val INSTAGRAM_FLAG = 3
private const val FACEBOOK_FLAG = 4
private const val PASSPORT_FLAG = 5
private const val BINANCE_FLAG = 6
