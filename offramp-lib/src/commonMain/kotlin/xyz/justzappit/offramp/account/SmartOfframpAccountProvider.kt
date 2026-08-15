// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.account

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.justzappit.evm.hd.EvmKey
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.signer.ThirdwebSmartAccount
import xyz.justzappit.evm.types.Address

/**
 * The owner key (self-custodial, from the seed via [OfframpAccountProvider]) plus the ERC-4337
 * smart-account [address] it controls. The owner signs UserOperations and EIP-191 messages; the
 * smart account is the on-chain identity — the `msg.sender` the Diamond sees, the recipient of
 * placed orders, and the bridge-in target (it can hold USDC while still counterfactual).
 */
data class OfframpSmartAccount(
    val owner: EvmKey,
    val address: Address
)

/**
 * Resolves the smart account by deriving the owner key and asking the factory for the
 * counterfactual address (`getAddress`). Works before deployment — the address is deterministic
 * from the owner key, and the account is lazily deployed inside its first sponsored UserOp.
 *
 * Cached against the owner it was derived for, so a seed change resolves afresh rather than
 * handing the new wallet the previous one's account.
 */
class SmartOfframpAccountProvider(
    private val accountProvider: OfframpAccountProvider,
    private val rpc: BaseRpcClient,
    private val accountFactory: Address,
) {
    private val mutex = Mutex()
    private var cached: OfframpSmartAccount? = null

    suspend fun resolve(): OfframpSmartAccount {
        val owner = accountProvider.nextOfframpAccount()
        return mutex.withLock {
            cached?.takeIf { it.owner.address == owner.address } ?: derive(owner).also { cached = it }
        }
    }

    private suspend fun derive(owner: EvmKey): OfframpSmartAccount {
        val returned =
            rpc.ethCall(
                to = accountFactory,
                data = ThirdwebSmartAccount.getAddressCalldata(owner.address),
            )
        require(returned.size >= Address.LEN_BYTES) {
            "factory getAddress returned ${returned.size} bytes, expected an address word"
        }
        val address = Address.fromBytes(returned.copyOfRange(returned.size - Address.LEN_BYTES, returned.size))
        return OfframpSmartAccount(owner, address)
    }
}
