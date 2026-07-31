// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.signer

import xyz.justzappit.evm.abi.AbiAddress
import xyz.justzappit.evm.abi.AbiBytes
import xyz.justzappit.evm.abi.AbiEncoder
import xyz.justzappit.evm.abi.AbiUint
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.Wei

/**
 * Calldata for thirdweb's prebuilt ERC-4337 v0.6 `Account` and its `AccountFactory` — the same
 * contract family p2p.me uses. The factory is pre-deployed by thirdweb on every chain at one
 * address (see [P2pNetworkConfig]); we deploy nothing.
 */
object ThirdwebSmartAccount {
    /** `Account.execute(target, value, data)` — the single inner call a UserOperation carries. */
    fun executeCalldata(to: Address, value: Wei, data: ByteArray): ByteArray =
        AbiEncoder.encodeFunctionCall(
            "execute(address,uint256,bytes)",
            listOf(AbiAddress(to), AbiUint(value.value), AbiBytes(data)),
        )

    /** `AccountFactory.createAccount(admin, data)` — runs inside the first UserOp's initCode. */
    fun createAccountCalldata(owner: Address, data: ByteArray = EMPTY): ByteArray =
        AbiEncoder.encodeFunctionCall(
            "createAccount(address,bytes)",
            listOf(AbiAddress(owner), AbiBytes(data)),
        )

    /** `AccountFactory.getAddress(admin, data)` — view call returning the counterfactual account address. */
    fun getAddressCalldata(owner: Address, data: ByteArray = EMPTY): ByteArray =
        AbiEncoder.encodeFunctionCall(
            "getAddress(address,bytes)",
            listOf(AbiAddress(owner), AbiBytes(data)),
        )

    /** v0.6 initCode = factory address ‖ createAccount calldata. Empty once the account is deployed. */
    fun initCode(factory: Address, owner: Address, data: ByteArray = EMPTY): ByteArray =
        factory.bytes + createAccountCalldata(owner, data)

    private val EMPTY = ByteArray(0)
}
