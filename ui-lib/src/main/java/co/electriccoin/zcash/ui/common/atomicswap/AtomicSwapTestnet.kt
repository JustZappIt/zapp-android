// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import co.electriccoin.zcash.ui.common.privateusd.Sepolia
import io.ktor.http.Url
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.atomicswap.SwapDeployment
import xyz.justzappit.offramp.atomicswap.SwapTokenIssuer
import xyz.justzappit.offramp.atomicswap.SwapZcashNetwork
import xyz.justzappit.offramp.atomicswap.TokenAttestation
import xyz.justzappit.offramp.atomicswap.ZcashDepositTerms
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.railgun.RailgunNetwork
import kotlin.time.Duration.Companion.minutes

object AtomicSwapTestnet {
    // 0.1 of the test token, whose relayer asks 0.02.
    private const val MAX_RELAYER_FEE = 100_000L

    // The testnet maker counts a deposit, and an escrow, after 2 confirmations: under 3 minutes on Zcash, with t0 13
    // minutes out. Records kept with the defaults keep theirs.
    private const val CONFIRMATIONS = 2
    private const val MIN_SECONDS_TO_T0 = 9 * 60L

    // In the token's base units; the hosted services start above the most a relayer may keep.
    private const val MIN_AMOUNT = 110_000L
    private const val MAX_AMOUNT = 20_000_000L

    // The hosted issuer's token key and the maker's key for handing tokens back, base64url SPKI.
    private const val ISSUER_KEY =
        "MIIBUjA9BgkqhkiG9w0BAQowMKANMAsGCWCGSAFlAwQCAqEaMBgGCSqGSIb3DQEBCDALBglghkgBZQMEAgKiAwIBMAOCAQ8AMI" +
            "IBCgKCAQEAqAp6ClskrSdfUjiFvS2H9eTxKKNxrtVPM-n0n8WCgCj508sXiyQj21W9Dpjki-JYLbYS7xkHaTCuplU7-Kg6EFK_" +
            "xwFB-BmQZcW9qk1GTYcZAqJ5YmfttMFHMDsdroUq5v6RnQxLRINIh5UIiFgn_516Wp2CcizaU-lwuwP4f8hp-0cLvynQ119pUT" +
            "IfwgaYhrRaxngHFu0JnQF6l4AK4rhtIt6g7AG_bSSV06cYowKuOVEncE45CaUDOUJbP18MfRSP42HPnWZ0-h6n3_uBYvxHYsKp" +
            "7KQ5_Hr-QpOafUoXhFANv4mfpk3MUQxo2m7vZZAMaFa9ELsIIiaLkACGhQIDAQAB"
    private const val RETURN_KEY =
        "MIIBUjA9BgkqhkiG9w0BAQowMKANMAsGCWCGSAFlAwQCAqEaMBgGCSqGSIb3DQEBCDALBglghkgBZQMEAgKiAwIBMAOCAQ8AMI" +
            "IBCgKCAQEA5boFzxSM2UDd-NfwWUnT_lVo23Td6wOH5twYjLo2wLDEWmGKSNBgdZy3t3JC2JkzD8j9rVRdehpll_i3GRKmUq0w" +
            "r6xkHHuHxswr3TehMUqUrztg-nxDS4U2f0V0yYno3I5YPo2SByOz_-jHNWM6Mlx-TZsWzALQTb3NmRXdynqecz3-8hqd_OPhUJ" +
            "tH7M3pK9ikkUbSzV1ofXkyM6FnaNAGkHZ1kAMEF2pGe69tOJJqGq6wpTuZMtzaaxu9t1C4xoR7R7pbdkjPM_Wxd15LoXa1N26V" +
            "-PToIeD06VmwX51VjrO_2VHWFat4Rtu7eLPeiEX-5kKsyvCY7EhpkNK1DQIDAQAB"

    /** The hosted testnet, which new conversions either way go to. */
    val deployment =
        AtomicSwapDeployment(
            swap =
                SwapDeployment(
                    makerUrl = Url("https://zecswap-testnet.pepeman931.workers.dev/maker"),
                    relayerUrl = Url("https://zecswap-testnet.pepeman931.workers.dev/relayer"),
                    rpcUrl = Sepolia.RPC_URL,
                    chainId = Sepolia.CHAIN_ID,
                    contract = Address.parse("0xD75Efc6a157CC0A95f66962DA86DDf35d9F2617c"),
                    token = Sepolia.TEST_USD,
                    railgunProxy = Sepolia.RAILGUN_PROXY,
                    maker = Address.parse("0x2bac02b5032e9092493814c705f156b49e288922"),
                    relayer = Address.parse("0xd9633572041886fa7584a2e12f36c8c7f1126412"),
                    maxRelayerFee = Usdc6.ofMicros(MAX_RELAYER_FEE),
                    escrowConfirmations = CONFIRMATIONS.toLong(),
                    zcashNetwork = SwapZcashNetwork.TESTNET,
                    zcashConfirmations = CONFIRMATIONS,
                    tokenIssuer =
                        SwapTokenIssuer(
                            url = Url("https://zecswap-testnet.pepeman931.workers.dev/issuer"),
                            name = "zecswap-testnet-issuer",
                            tokenKey = ISSUER_KEY,
                            attestation = TokenAttestation.INSECURE_TEST,
                            returnKey = RETURN_KEY,
                        ),
                ),
            deposits = ZcashDepositTerms(MIN_SECONDS_TO_T0),
            railgunNetwork = RailgunNetwork.SEPOLIA,
            explorerTxUrl = Sepolia.EXPLORER_TX_URL,
            minAmount = Usdc6.ofMicros(MIN_AMOUNT),
            maxAmount = Usdc6.ofMicros(MAX_AMOUNT),
            screeningTime = 1.minutes,
        )
}
