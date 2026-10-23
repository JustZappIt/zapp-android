// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.abi

import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId

/** EIP-712's hashes, for structs whose fields are all one word each: addresses, integers and `bytes32`. */
internal object Eip712 {
    const val DOMAIN_TYPE: String =
        "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"

    private val domainTypeHash = typeHash(DOMAIN_TYPE)

    fun typeHash(type: String): ByteArray = keccak256(type.encodeToByteArray())

    /** The separator of a domain with all four of [DOMAIN_TYPE]'s fields, as contracts and tokens compute it. */
    fun domainSeparator(
        name: String,
        version: String,
        chainId: ChainId,
        verifyingContract: Address,
    ): ByteArray =
        hashStruct(
            domainTypeHash,
            AbiBytes32(keccak256(name.encodeToByteArray())),
            AbiBytes32(keccak256(version.encodeToByteArray())),
            AbiUint(bigIntegerValueOf(chainId.value)),
            AbiAddress(verifyingContract),
        )

    /** `keccak256(abi.encode(typeHash, fields…))`, which is EIP-712's `hashStruct` while every field is one word. */
    fun hashStruct(
        typeHash: ByteArray,
        vararg fields: AbiArg
    ): ByteArray {
        require(typeHash.size == WORD) { "a type hash is $WORD bytes" }
        require(fields.none { it.isDynamic || it.headSize != WORD }) { "every field is one word" }
        return keccak256(AbiEncoder.encode(listOf(AbiBytes32(typeHash)) + fields))
    }

    /** What is signed: `keccak256(0x19 ‖ 0x01 ‖ domainSeparator ‖ structHash)`. */
    fun digest(
        domainSeparator: ByteArray,
        structHash: ByteArray
    ): ByteArray {
        require(domainSeparator.size == WORD) { "a domain separator is $WORD bytes" }
        require(structHash.size == WORD) { "a struct hash is $WORD bytes" }
        return keccak256(byteArrayOf(EIP191_PREFIX, EIP712_VERSION) + domainSeparator + structHash)
    }

    private const val EIP191_PREFIX: Byte = 0x19
    private const val EIP712_VERSION: Byte = 0x01
}
