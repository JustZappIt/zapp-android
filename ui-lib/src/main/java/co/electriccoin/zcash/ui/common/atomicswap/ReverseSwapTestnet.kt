// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import xyz.justzappit.offramp.atomicswap.ReverseDeployment

object ReverseSwapTestnet {
    val deployment =
        ReverseDeployment(
            makerUrl = "https://zecswap-testnet.pepeman931.workers.dev/maker",
            relayerUrl = "https://zecswap-testnet.pepeman931.workers.dev/relayer",
            rpcUrl = "https://ethereum-sepolia-rpc.publicnode.com",
            chainId = 11_155_111,
            contract = "0xbd9a37f47a988aefc4d80395727f41feb698e225",
            token = "0x5764d0044bef5aa839e0ddafe2073421101b9ed8",
            railgun = "0xecfcf3b4ec647c4ca6d49108b311b7a7c9543fea",
            maker = "0x2bac02b5032e9092493814c705f156b49e288922",
            relayer = "0xd9633572041886fa7584a2e12f36c8c7f1126412",
            maxRefundFee = "100000",
        )
}
