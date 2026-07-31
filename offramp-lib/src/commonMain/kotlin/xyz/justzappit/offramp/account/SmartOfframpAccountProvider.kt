// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.account

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
 */
class SmartOfframpAccountProvider(
    private val accountProvider: OfframpAccountProvider,
    private val rpc: BaseRpcClient,
    private val accountFactory: Address,
) {
    suspend fun resolve(): OfframpSmartAccount {
        val owner = accountProvider.nextOfframpAccount()
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
