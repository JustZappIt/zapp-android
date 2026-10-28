// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.deposits

import xyz.justzappit.evm.abi.AbiAddress
import xyz.justzappit.evm.abi.AbiArg
import xyz.justzappit.evm.abi.AbiBytes32
import xyz.justzappit.evm.abi.AbiEncoder
import xyz.justzappit.evm.abi.AbiUint
import xyz.justzappit.evm.abi.Eip712
import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId

/**
 * The Railgun note a deposit or a rescue shields to, as the Deposits contract takes it: its note public key [npk], then
 * its `ShieldCiphertext`, the three words of [encryptedBundle] and [shieldKey]. The wallet builds it from its seed to
 * its own Railgun keys and opens it as its receiver would before anything signs for it: Railgun takes any ciphertext,
 * and a note its receiver can't open is lost. Never show or log one, so [toString] tells nothing of it.
 */
class DepositNote(
    npk: ByteArray,
    encryptedBundle: List<ByteArray>,
    shieldKey: ByteArray,
) {
    private val words: List<ByteArray> = (listOf(npk) + encryptedBundle + listOf(shieldKey)).map { it.copyOf() }

    init {
        require(encryptedBundle.size == BUNDLE_WORDS) { "an encrypted bundle is $BUNDLE_WORDS words" }
        require(words.all { it.size == WORD_BYTES }) { "each of a note's words is $WORD_BYTES bytes" }
    }

    val npk: ByteArray get() = words.first().copyOf()

    val encryptedBundle: List<ByteArray> get() = words.subList(1, 1 + BUNDLE_WORDS).map { it.copyOf() }

    val shieldKey: ByteArray get() = words.last().copyOf()

    /**
     * `keccak256(abi.encode(npk, ciphertext))`: what the Deposits contract knows the note by (`noteCommitment`), the
     * `note` of the `DepositShielded` event that proves a deposit landed, and the `note` a rescue signs for.
     */
    val commitment: ByteArray get() = keccak256(words.fold(ByteArray(0)) { encoded, word -> encoded + word })

    /** Its five words as `abi.encode` lays out `npk` and a `ShieldCiphertext`: the bundle's three inline. */
    internal fun abiWords(): List<AbiArg> = words.map { AbiBytes32(it.copyOf()) }

    override fun equals(other: Any?): Boolean =
        other is DepositNote && words.indices.all { words[it].contentEquals(other.words[it]) }

    override fun hashCode(): Int = words.fold(0) { hash, word -> HASH_MULTIPLIER * hash + word.contentHashCode() }

    override fun toString(): String = "DepositNote"

    private companion object {
        const val BUNDLE_WORDS = 3
        const val HASH_MULTIPLIER = 31
    }
}

/**
 * An EIP-3009 token's EIP-712 domain: its own `name()` and `version()`, read from the token and never assumed (USDC's
 * are `"USD Coin"` and `"2"`, testnet tUSD's `"ZecSwap Test USD"` and `"2"`), on [chainId] at [token]. Before signing
 * under it, require [separator] to equal the token's `DOMAIN_SEPARATOR()` (`0x3644e515`).
 */
data class TokenDomain(
    val name: String,
    val version: String,
    val chainId: ChainId,
    val token: Address,
) {
    /** The domain separator, which must equal the token's own `DOMAIN_SEPARATOR()`. */
    fun separator(): ByteArray = Eip712.domainSeparator(name, version, chainId, token)

    /** The digest [ReceiveAuthorization.from]'s key signs ([DepositKey.sign]) for the token to move [authorization]. */
    fun receiveWithAuthorization(authorization: ReceiveAuthorization): ByteArray =
        Eip712.digest(
            separator(),
            Eip712.hashStruct(
                RECEIVE_WITH_AUTHORIZATION_TYPEHASH,
                AbiAddress(authorization.from),
                AbiAddress(authorization.to),
                AbiUint(authorization.value),
                AbiUint(bigIntegerValueOf(authorization.validAfter)),
                AbiUint(bigIntegerValueOf(authorization.validBefore)),
                AbiBytes32(authorization.nonce),
            ),
        )

    internal companion object {
        const val RECEIVE_WITH_AUTHORIZATION_TYPE: String =
            "ReceiveWithAuthorization(address from,address to,uint256 value,uint256 validAfter," +
                "uint256 validBefore,bytes32 nonce)"

        val RECEIVE_WITH_AUTHORIZATION_TYPEHASH = Eip712.typeHash(RECEIVE_WITH_AUTHORIZATION_TYPE)
    }
}

/**
 * What a `receiveWithAuthorization` moves: [value] in the token's base units from [from] to [to], which alone may
 * submit it, once per [nonce], after [validAfter] and before [validBefore] (Unix seconds). A deposit's [from] is the
 * deposit address, its [to] the Deposits contract, its [value] the address's whole balance, its [validAfter] 0 and its
 * [nonce] [DepositsDomain.depositNonce]; [validBefore] is an hour from now, or `maxValidity` less a minute if sooner.
 */
class ReceiveAuthorization(
    val from: Address,
    val to: Address,
    val value: BigInteger,
    val validAfter: Long,
    val validBefore: Long,
    nonce: ByteArray,
) {
    private val nonceBytes = nonce.copyOf()

    init {
        require(value.signum() >= 0 && value.bitLength() <= UINT256_BITS) { "a value is a uint256" }
        require(validAfter >= 0 && validBefore >= 0) { "the validity's bounds are Unix seconds" }
        require(nonce.size == WORD_BYTES) { "a nonce is $WORD_BYTES bytes" }
    }

    val nonce: ByteArray get() = nonceBytes.copyOf()
}

/**
 * One Deposits contract, [contract] on [chainId]: what a deposit's authorization commits to there, and what the owner
 * of a deposit address signs to shield again what Railgun's screening sent back to the address's vault.
 *
 * A deposit, as zecSwap's `docs/deposits.md` signs it: the note built from the seed ([DepositNote]); its nonce,
 * [depositNonce] with the fee and the relayer's deposits account; then [TokenDomain.receiveWithAuthorization] of a
 * [ReceiveAuthorization] with that nonce, signed by the address's [DepositKey]. A rescue signs [rescue] the same way.
 */
data class DepositsDomain(
    val chainId: ChainId,
    val contract: Address,
) {
    /**
     * `depositNonce(npk, ciphertext, fee, relayer)`, the EIP-3009 nonce of an authorization to shield to [note], paying
     * [fee] (the token's base units, a `uint128`) to [relayer], which alone can send it: the relayer's deposits
     * account, the terms' `deposits.relayer`, never their top-level `relayer`. A plain hash, not an EIP-712 digest:
     * `keccak256(abi.encode(DEPOSIT_TYPEHASH, chainId, contract, npk, encryptedBundle, shieldKey, fee, relayer))`.
     */
    fun depositNonce(
        note: DepositNote,
        fee: BigInteger,
        relayer: Address,
    ): ByteArray {
        requireUint128(fee)
        val words =
            listOf(AbiBytes32(DEPOSIT_TYPEHASH), AbiUint(bigIntegerValueOf(chainId.value)), AbiAddress(contract)) +
                note.abiWords() +
                listOf(AbiUint(fee), AbiAddress(relayer))
        return keccak256(AbiEncoder.encode(words))
    }

    /**
     * The EIP-712 digest [from]'s key signs to rescue its vault: to shield what Railgun sent back to it again, to
     * [note], less [fee] (the terms' `deposits.fee` alone) for [relayer] (the terms' `deposits.relayer`), with [nonce]
     * the contract's `rescueNonces(from)` and a [deadline] (Unix seconds) 15 minutes to `maxValidity` away.
     */
    fun rescue(
        from: Address,
        note: DepositNote,
        relayer: Address,
        fee: BigInteger,
        nonce: Long,
        deadline: Long,
    ): ByteArray {
        requireUint128(fee)
        require(nonce >= 0 && deadline >= 0) { "a rescue's nonce and deadline are uint64s" }
        return Eip712.digest(
            separator(),
            Eip712.hashStruct(
                RESCUE_TYPEHASH,
                AbiAddress(from),
                AbiBytes32(note.commitment),
                AbiAddress(relayer),
                AbiUint(fee),
                AbiUint(bigIntegerValueOf(nonce)),
                AbiUint(bigIntegerValueOf(deadline)),
            ),
        )
    }

    /** The contract's EIP-712 domain separator, of `"ZecSwap Deposits"` version `"1"`, which rescues sign under. */
    fun separator(): ByteArray = Eip712.domainSeparator(NAME, VERSION, chainId, contract)

    internal companion object {
        const val NAME: String = "ZecSwap Deposits"
        const val VERSION: String = "1"
        const val DEPOSIT_TYPE: String =
            "Deposit(uint256 chainId,address deposits,bytes32 npk,bytes32[3] encryptedBundle," +
                "bytes32 shieldKey,uint128 fee,address relayer)"
        const val RESCUE_TYPE: String =
            "Rescue(address from,bytes32 note,address relayer,uint128 fee,uint64 nonce,uint64 deadline)"

        val DEPOSIT_TYPEHASH = Eip712.typeHash(DEPOSIT_TYPE)
        val RESCUE_TYPEHASH = Eip712.typeHash(RESCUE_TYPE)
    }
}

private fun requireUint128(fee: BigInteger) {
    require(fee.signum() >= 0 && fee.bitLength() <= UINT128_BITS) { "a fee is a uint128" }
}

private const val UINT128_BITS = 128
private const val UINT256_BITS = 256
