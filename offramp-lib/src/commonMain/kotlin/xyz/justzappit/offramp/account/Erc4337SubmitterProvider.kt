// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.account

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.BundlerClient
import xyz.justzappit.evm.signer.Erc4337Submitter
import xyz.justzappit.evm.signer.TxSubmitter
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.config.P2pNetworkConfig

/** The smart account, and the one submitter allowed to spend from it. */
data class SubmittingAccount(
    val address: Address,
    val submitter: TxSubmitter,
)

/**
 * One [Erc4337Submitter] per smart account, for the whole app.
 *
 * The submitter owns a local nonce cursor, so one per operation gives every concurrent cash-out,
 * withdrawal, approval and top-up its own idea of the next nonce. Two of them read the EntryPoint at
 * the same time, sign different operations against the same value, and the bundler keeps one — the
 * other's caller waits out the receipt timeout on an operation that will never appear.
 *
 * Keyed by address rather than memoised outright, so a wallet reset — new seed, new owner key, new
 * counterfactual address — starts from a fresh cursor instead of inheriting the old wallet's.
 */
class Erc4337SubmitterProvider(
    private val rpc: BaseRpcClient,
    private val bundler: BundlerClient,
    private val network: P2pNetworkConfig,
    private val accountProvider: SmartOfframpAccountProvider,
) {
    private val mutex = Mutex()
    private var cached: SubmittingAccount? = null

    suspend fun resolve(): SubmittingAccount {
        val account = accountProvider.resolve()
        return mutex.withLock {
            cached?.takeIf { it.address == account.address } ?: build(account).also { cached = it }
        }
    }

    private fun build(account: OfframpSmartAccount) =
        SubmittingAccount(
            address = account.address,
            submitter =
                Erc4337Submitter(
                    rpc = rpc,
                    bundler = bundler,
                    entryPoint = network.entryPointAddress,
                    accountFactory = network.accountFactoryAddress,
                    owner = account.owner,
                    smartAccount = account.address,
                    chainId = network.chainId,
                ),
        )
}
