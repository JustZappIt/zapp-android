// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.atomicswap

import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.p2p.Usdc6

/** What `cargo run -p zecswap-client --example vectors` prints in zecSwap (feature/terms-hash, bf5f4cf). */
object ZecSwapVectors {
    val USER_SHARE =
        SwapShare.parse(
            "0x0b42629d5b3f787aba7ccde87574c13f6db6b8fccb7c5cfeb3f4e4081c756461" +
                "311662156525fcaa692aeef5d2362c2a9ab2083cd6d968c618aec72617aa925a",
        )
    val MAKER_SHARE =
        SwapShare.parse(
            "0x187d300ebb59a5c9e7c9e61debd0b535a8a5cbbad4a5c46ac2a0597a06c13ee4" +
                "329a369cf900745cdca87863f3f00de64d16e088bb1f5da341a2977547f9080a",
        )
    val AUTH = Address.parse("0x757de38c2d9880e44ab59827d1622403fbf88ff5")
    val MAKER = Address.parse("0x09eD1F966745Be18C711C346242c0974DAd7c3e5")
    const val SWAP_ID = "0x297f1ca9d44ff7136dbddb0720ecadc040229e22c03ad0f9e4c648212bfc7b66"
    const val REVERSE_SWAP_ID = "0x0a5ff70eda9eff985515f84e23bcff13da416fa9ed0500e251e248c8f5ff5436"
    val PAYS_ACCOUNT =
        SwapTerms(
            maker = Address.parse("0x09ed1f966745be18c711c346242c0974dad7c3e5"),
            token = Address.parse("0x3333333333333333333333333333333333333333"),
            amount = Usdc6.ofMicros(150_000_000),
            makerKey = MAKER_SHARE,
            userKey = USER_SHARE,
            user = Address.parse("0x4444444444444444444444444444444444444444"),
            t0 = 1_790_003_600,
            t1 = 1_790_007_200,
            payoutNote = NoteCommitment.parse("0x0000000000000000000000000000000000000000000000000000000000000000"),
        )
    const val PAYS_ACCOUNT_HASH = "0x909f60119225ae64356011c63945694ed0dc6aaaa8cd6002a897df9ab76efc66"
    val PAYS_RAILGUN =
        SwapTerms(
            maker = Address.parse("0x09ed1f966745be18c711c346242c0974dad7c3e5"),
            token = Address.parse("0x3333333333333333333333333333333333333333"),
            amount = Usdc6.ofMicros(150_000_000),
            makerKey = MAKER_SHARE,
            userKey = USER_SHARE,
            user = Address.parse("0x757de38c2d9880e44ab59827d1622403fbf88ff5"),
            t0 = 1_790_003_600,
            t1 = 1_790_007_200,
            payoutNote = NoteCommitment.parse("0x5af6901ba7cb01f49785a29c4a2e57e31af3e53382ce3dd2e35678897515ffc1"),
        )
    const val PAYS_RAILGUN_HASH = "0xb7ca9333e9e443218d19c3b8aa345fa67a671ac24060877de1498efb01c99a29"
    val REVERSE =
        SwapTerms(
            maker = Address.parse("0x757de38c2d9880e44ab59827d1622403fbf88ff5"),
            token = Address.parse("0x3333333333333333333333333333333333333333"),
            amount = Usdc6.ofMicros(50_000_000),
            makerKey = USER_SHARE,
            userKey = MAKER_SHARE,
            user = Address.parse("0x09ed1f966745be18c711c346242c0974dad7c3e5"),
            t0 = 1_790_003_600,
            t1 = 1_790_007_200,
            payoutNote = NoteCommitment.parse("0x0000000000000000000000000000000000000000000000000000000000000000"),
        )
    const val REVERSE_HASH = "0xa295e46f6db15997af4a950b5e6deed6ae5d3161b6571b2f445a34bc6570cead"
}
