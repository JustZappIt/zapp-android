// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.reputation

import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.p2p.CurrencyCode
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fixtures are real: calldata from `cast calldata`, return data from `eth_call` against Base
 * mainnet on 2026-08-29, and the three verified addresses are wallets that actually completed a
 * `socialVerify` on this contract. The index mapping in particular is asserted against the chain
 * rather than against our own reading of it — a synthetic blob would only prove we agree with
 * ourselves, and getting index 5/6 backwards shows the wrong platform as verified.
 */
class ReputationCallsTest {
    @Test
    fun `calldata matches cast for every reputation read`() {
        assertEquals(RMUSERS_CALLDATA, ReputationCalls.rmusersCalldata(X_VERIFIED).hex())
        assertEquals(SOCIAL_VERIFIED_CALLDATA, ReputationCalls.socialVerifiedCalldata(X_VERIFIED).hex())
        assertEquals(
            USER_TX_LIMIT_CALLDATA,
            ReputationCalls.userTxLimitCalldata(X_VERIFIED, CurrencyCode.Inr).hex(),
        )
        assertEquals(MAX_BUY_CALLDATA, ReputationCalls.maxBuyTxLimitCalldata(CurrencyCode.Inr).hex())
        assertEquals(RATIONAL_CALLDATA, ReputationCalls.rpPerUsdcLimitCalldata(CurrencyCode.Inr).hex())
        assertEquals("0x30228d8c", ReputationCalls.rpAwardCalldata(SocialPlatform.LinkedIn).hex())
        assertEquals("0x4e47c98c", ReputationCalls.rpAwardCalldata(SocialPlatform.Binance).hex())
    }

    @Test
    fun `every platform has a distinct rp getter and flag index`() {
        val selectors = SocialPlatform.entries.map { ReputationCalls.rpAwardCalldata(it).hex() }
        assertEquals(SocialPlatform.entries.size, selectors.toSet().size)
        val indices = SocialPlatform.entries.map { it.socialVerifiedIndex }
        assertEquals(SocialPlatform.entries.size, indices.toSet().size)
        assertTrue(indices.all { it in 0 until SocialPlatform.SOCIAL_VERIFIED_FLAGS })
        assertFalse(SocialPlatform.PASSPORT_FLAG_INDEX in indices, "passport is not a Zapp platform")
    }

    @Test
    fun `socialVerified index mapping matches the chain`() {
        assertEquals(setOf(SocialPlatform.LinkedIn), ReputationCalls.decodeSocialVerified(LINKEDIN_FLAGS.hexToBytes()))
        assertEquals(setOf(SocialPlatform.X), ReputationCalls.decodeSocialVerified(X_FLAGS.hexToBytes()))
        // Index 6, the trap: p2p's tuple puts passport at 5 and Binance last.
        assertEquals(setOf(SocialPlatform.Binance), ReputationCalls.decodeSocialVerified(BINANCE_FLAGS.hexToBytes()))
    }

    @Test
    fun `a passport-only flag word claims no platform`() {
        val passportOnly = flagsWord(SocialPlatform.PASSPORT_FLAG_INDEX)
        assertEquals(emptySet(), ReputationCalls.decodeSocialVerified(passportOnly.hexToBytes()))
    }

    @Test
    fun `rmusers decodes points and the blacklist flag`() {
        val verified = ReputationCalls.decodeRmUser(RMUSERS_X.hexToBytes())
        assertEquals("100", verified.reputationPoints.toString())
        assertFalse(verified.isBlacklisted)

        // Real blacklisted wallet: a Binance verification on record, reputation zeroed anyway.
        val blacklisted = ReputationCalls.decodeRmUser(RMUSERS_BLACKLISTED.hexToBytes())
        assertEquals("0", blacklisted.reputationPoints.toString())
        assertTrue(blacklisted.isBlacklisted)
    }

    @Test
    fun `userTxLimit decodes buy and sell in micro-USDC`() {
        val limits = ReputationCalls.decodeUserTxLimits(USER_TX_LIMIT_X.hexToBytes())
        assertEquals(Usdc6.ofMicros(100_000_000L), limits.buy)
        assertEquals(Usdc6.ofMicros(200_000_000L), limits.sell)
    }

    @Test
    fun `INR pays one dollar of limit per reputation point`() {
        val rational = ReputationCalls.decodeRpPerUsdcLimit(RATIONAL_ONE_TO_ONE.hexToBytes())
        assertTrue(rational.isReadable)
        // The wallet above: 100 RP, and the Diamond really does report a $100 INR buy limit.
        assertEquals("100000000", rational.limitMicrosFor(bigIntegerValueOf(100)).toString())
    }

    @Test
    fun `truncated return data throws rather than decoding as zero`() {
        // A short read must fail loudly: silently yielding 0 RP shows a verified user a wall.
        assertFailsWith<IllegalArgumentException> {
            ReputationCalls.decodeRmUser(RMUSERS_X.hexToBytes().copyOfRange(0, 64))
        }
        assertFailsWith<IllegalArgumentException> {
            ReputationCalls.decodeSocialVerified(X_FLAGS.hexToBytes().copyOfRange(0, 96))
        }
    }

    private fun ByteArray.hex(): String = "0x" + toHex()

    private fun flagsWord(trueIndex: Int): String =
        (0 until SocialPlatform.SOCIAL_VERIFIED_FLAGS).joinToString("") { i ->
            if (i == trueIndex) "0".repeat(63) + "1" else "0".repeat(64)
        }

    private companion object {
        // Wallets that really verified on the ReputationManager, found in its SocialVerified logs.
        val X_VERIFIED: Address = Address.parse("0x448f857ea117138e85d062c6ce89e90a337874d6")

        const val RMUSERS_CALLDATA =
            "0xcdec461b000000000000000000000000448f857ea117138e85d062c6ce89e90a337874d6"
        const val SOCIAL_VERIFIED_CALLDATA =
            "0x77f133af000000000000000000000000448f857ea117138e85d062c6ce89e90a337874d6"
        const val USER_TX_LIMIT_CALLDATA =
            "0x6d5da5ad" +
                "000000000000000000000000448f857ea117138e85d062c6ce89e90a337874d6" +
                "494e520000000000000000000000000000000000000000000000000000000000"
        const val MAX_BUY_CALLDATA =
            "0xad641d16494e520000000000000000000000000000000000000000000000000000000000"
        const val RATIONAL_CALLDATA =
            "0x7608ccbf494e520000000000000000000000000000000000000000000000000000000000"

        // socialVerified(address) returns: (linkedIn, gitHub, x, instagram, facebook, passport, binance)
        val LINKEDIN_FLAGS =
            "0000000000000000000000000000000000000000000000000000000000000001" +
                "0000000000000000000000000000000000000000000000000000000000000000".repeat(6)
        val X_FLAGS =
            "0000000000000000000000000000000000000000000000000000000000000000".repeat(2) +
                "0000000000000000000000000000000000000000000000000000000000000001" +
                "0000000000000000000000000000000000000000000000000000000000000000".repeat(4)
        val BINANCE_FLAGS =
            "0000000000000000000000000000000000000000000000000000000000000000".repeat(6) +
                "0000000000000000000000000000000000000000000000000000000000000001"

        const val RMUSERS_X =
            "0000000000000000000000000000000000000000000000000000000000000064" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000"
        const val RMUSERS_BLACKLISTED =
            "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000001"
        const val USER_TX_LIMIT_X =
            "0000000000000000000000000000000000000000000000000000000005f5e100" +
                "000000000000000000000000000000000000000000000000000000000bebc200"
        const val RATIONAL_ONE_TO_ONE =
            "0000000000000000000000000000000000000000000000000000000000000001" +
                "0000000000000000000000000000000000000000000000000000000000000001"
    }
}
