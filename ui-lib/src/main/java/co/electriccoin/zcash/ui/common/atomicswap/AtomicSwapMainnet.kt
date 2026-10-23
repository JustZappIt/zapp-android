// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import co.electriccoin.zcash.ui.common.privateusd.Ethereum
import co.electriccoin.zcash.ui.common.privateusd.RailgunSendsPin
import io.ktor.http.Url
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.atomicswap.SwapDeployment
import xyz.justzappit.offramp.atomicswap.SwapTokenIssuer
import xyz.justzappit.offramp.atomicswap.SwapWindows
import xyz.justzappit.offramp.atomicswap.SwapZcashNetwork
import xyz.justzappit.offramp.atomicswap.ZcashDepositTerms
import xyz.justzappit.railgun.RailgunAddress
import xyz.justzappit.railgun.RailgunNetwork
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

object AtomicSwapMainnet {
    // The mainnet maker counts a deposit after 2 confirmations, with t0 90 minutes out and t1 an hour after it, and
    // cancels a deposit not confirmed in full an hour before t0. An escrow gets the default 3.
    private const val ZCASH_CONFIRMATIONS = 2

    // A deposit starts only with t0 at least 70 minutes away: the maker's hour before t0, and 10 minutes to confirm.
    private const val MIN_SECONDS_TO_T0 = 70 * 60L

    // The relayer's own 0zk address, which a private send's fee note pays.
    private const val RELAYER_RAILGUN_ADDRESS =
        "0zk1qy860uut20m8gd6fnhzvr0zqeyhs0ya5247x7h2eyedxsazx43w5nrv7j6fe3z53l78hn78kmslky2206lm75mclrv7dawd756m2" +
            "suy7l4zud2m4yhg6kcsn7ed"

    // The hosted issuer's token key and the maker's key for handing tokens back, base64url SPKI.
    private const val ISSUER_KEY =
        "MIIBUjA9BgkqhkiG9w0BAQowMKANMAsGCWCGSAFlAwQCAqEaMBgGCSqGSIb3DQEBCDALBglghkgBZQMEAgKiAwIBMAOCAQ8AMI" +
            "IBCgKCAQEAuA8Rq3Fo644QG48Kmxsyg1UFAKd-kfsS9t7BgPVwJqXHIEmlH4gS1UaxDBdu7awf0n9lv7yYozNTE5PysBh01LBh" +
            "GbcIMWyTSNXkF2l1Mi0AOVVhVJZr-KTyr0u6ZFI2EGRVxT1BF2B4QgzQgJ64xqj-tzX_ctpwYQiE1Gf74zG4Yd0mdEbDjTYhgz" +
            "oaR0lV5uHHSnVN2fmnlskAkZl6mfrJKfcfivp-UQ8qgBEs-7ke67lMhMyC3BxoDG7qoY0B4eIQRQ_-fu3eYT1RifuO5LgvMYkz" +
            "Re58X63emOvYls19En0Vbkeq2Y73kMRA2AOYMp5gD3DyF4t5LM535p4-GQIDAQAB"
    private const val RETURN_KEY =
        "MIIBUjA9BgkqhkiG9w0BAQowMKANMAsGCWCGSAFlAwQCAqEaMBgGCSqGSIb3DQEBCDALBglghkgBZQMEAgKiAwIBMAOCAQ8AMI" +
            "IBCgKCAQEArte6ewR0iOfbhxrByZ1vj_MMkKiQLQ-2R3OdCyHeKr_G4FrsCHvLhJ0uuu5r-papDogiMb_btM5-nPaOWzC1olxb" +
            "mEUypJWgz-yCXD3K4wp_jgeqlP7EnlJE3IT5iTwv_S1hK5yV4Fka5mRoyhvZlhvDW3eCWoMix15Fp-TD3AGm7GttqzW4QyLXV_" +
            "mQcnqlWPwuCacmTYch93eMZ7CaTHHRMmsfqR7_kZdskZh_JM55_lw_puDvvdRtu4vYItvo5wMI5mkwmSEwi6UahZDx_OI8IAF2" +
            "JpeqRIKwbhT6FVGwahDeu6PRTww71C0i13EOwC3GQ85pxDRIAzF01W14wQIDAQAB"

    /** The hosted mainnet, on its windows contract since 2026-10-22, which new conversions either way go to. */
    val deployment =
        AtomicSwapDeployment(
            swap =
                SwapDeployment(
                    makerUrl = Url("https://zecswap-mainnet.pepeman931.workers.dev/maker"),
                    relayerUrl = Url("https://zecswap-mainnet.pepeman931.workers.dev/relayer"),
                    rpcUrl = Ethereum.RPC_URL,
                    chainId = Ethereum.CHAIN_ID,
                    contract = Address.parse("0xeB3A00bcB6c9fF626fE57a5dBbDCf01730e7898f"),
                    token = Ethereum.USDC,
                    railgunProxy = Ethereum.RAILGUN_PROXY,
                    maker = Address.parse("0x4370eEaa6bEcf1dAeF21Deeb0379Eb7f6CaEf7eF"),
                    relayer = Address.parse("0xcBcb5f57C79C88a0E4BB450289dE2088958A017F"),
                    zcashNetwork = SwapZcashNetwork.MAINNET,
                    zcashConfirmations = ZCASH_CONFIRMATIONS,
                    tokenIssuer =
                        SwapTokenIssuer(
                            url = Url("https://zecswap-mainnet.pepeman931.workers.dev/issuer"),
                            name = "zecswap-mainnet-issuer",
                            tokenKey = ISSUER_KEY,
                            returnKey = RETURN_KEY,
                        ),
                    windows = SwapWindows(min = 60 * 60, late = 24 * 60 * 60),
                ),
            deposits = ZcashDepositTerms(MIN_SECONDS_TO_T0),
            railgunNetwork = RailgunNetwork.MAINNET,
            explorerTxUrl = Ethereum.EXPLORER_TX_URL,
            railscanTxUrl = Ethereum.RAILSCAN_TX_URL,
            screeningTime = 1.hours,
            railgunSends =
                RailgunSendsPin(
                    chainId = Ethereum.CHAIN_ID,
                    railgunProxy = Ethereum.RAILGUN_PROXY,
                    railgunAddress = RailgunAddress(RELAYER_RAILGUN_ADDRESS),
                    feeToken = Ethereum.USDC,
                ),
            makerDeadlines = MakerDeadlines(t0 = 90.minutes, t1 = 150.minutes),
        )

    /** The mainnet beta's lock contract until 2026-10-22: its conversions are history, and nothing drives them. */
    val retired =
        deployment.copy(
            swap =
                deployment.swap.copy(
                    contract = Address.parse("0x4708D7d36992b7e33Ad97a1ab0A31c3ac6C4fe6D"),
                    windows = null,
                ),
        )
}
