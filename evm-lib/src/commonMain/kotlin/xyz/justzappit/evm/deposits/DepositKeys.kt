// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.deposits

import xyz.justzappit.evm.deposits.DepositKeys.ACCOUNT_OFFSET
import xyz.justzappit.evm.hd.Bip32
import xyz.justzappit.evm.hd.Bip32.HARDENED
import xyz.justzappit.evm.hd.EvmKey
import xyz.justzappit.evm.hd.EvmKeyDerivation
import xyz.justzappit.evm.hd.ExtendedKey
import xyz.justzappit.evm.signer.SECP256K1_N
import xyz.justzappit.evm.signer.toUnsignedFieldBytes
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.toHex

/**
 * The wallet's deposit addresses: ordinary Ethereum accounts on BIP-44's `m/44'/60'/(1000 + account)'/0/index` on
 * mainnet and `m/44'/1'/(1000 + account)'/0/index` on testnet, from the wallet's BIP-39 seed, as zecSwap's
 * `derive_deposit_keys` has them (`docs/deposits.md`). No wallet opens account 1000 or above by default, so these never
 * mix with a user's own addresses, yet any wallet that takes a derivation path finds them; testnet's coin type keeps
 * testnet and mainnet addresses unlinked.
 *
 * `account` is the wallet's ZIP 32 account (Zapp's is 0) and `index` the deposit address's. The seed is the 64-byte
 * BIP-39 seed the swaps use: these functions read it, keep nothing of it or of any key derived from it, and leave
 * wiping it to its owner. A seed BIP-32 can't take (other than 16, 32 or 64 bytes), or an account or index it can't
 * reach, throws [IllegalArgumentException].
 */
object DepositKeys {
    /** The BIP-44 account of the wallet's account 0: deposit addresses take accounts from here on. */
    const val ACCOUNT_OFFSET: Int = 1000

    /**
     * [block] with deposit address [index]'s key, which is wiped once it returns: the key must not escape it. Sign with
     * [DepositKey.sign], over [TokenDomain.receiveWithAuthorization] for a deposit or [DepositsDomain.rescue] for a
     * rescue.
     */
    fun <T> withKey(
        seed: ByteArray,
        mainnet: Boolean,
        account: Int,
        index: Int,
        block: (DepositKey) -> T,
    ): T {
        val key = derive(seed, mainnet, account, index)
        return try {
            block(key)
        } finally {
            key.zeroize()
        }
    }

    /** Deposit address [index]. */
    fun address(
        seed: ByteArray,
        mainnet: Boolean,
        account: Int,
        index: Int,
    ): Address = withKey(seed, mainnet, account, index) { it.address }

    /** The deposit addresses at [indices], in order: a window of the scan that finds the used ones. */
    fun addresses(
        seed: ByteArray,
        mainnet: Boolean,
        account: Int,
        indices: IntRange,
    ): List<Address> {
        if (indices.isEmpty()) return emptyList()
        requireReachable(account, indices.first)
        // Every address is a child of the same node, `m/44'/coin'/(1000 + account)'/0`, derived once.
        val external = Bip32.derive(master(seed), accountPath(mainnet, account) + EXTERNAL_CHAIN)
        return try {
            indices.map { index ->
                val child = Bip32.derive(external.copy(), listOf(index))
                try {
                    addressOf(child.privateKey)
                } finally {
                    child.zeroize()
                }
            }
        } finally {
            external.zeroize()
        }
    }

    /** Deposit address [index]'s derivation path, written as wallets take it: `m/44'/60'/1000'/0/0` for the first. */
    internal fun path(
        mainnet: Boolean,
        account: Int,
        index: Int,
    ): String {
        requireReachable(account, index)
        return "m/$PURPOSE'/${coinType(mainnet)}'/${ACCOUNT_OFFSET + account}'/$EXTERNAL_CHAIN/$index"
    }

    internal fun derive(
        seed: ByteArray,
        mainnet: Boolean,
        account: Int,
        index: Int,
    ): DepositKey {
        requireReachable(account, index)
        val node = Bip32.derive(master(seed), accountPath(mainnet, account) + listOf(EXTERNAL_CHAIN, index))
        return try {
            DepositKey(EvmKeyDerivation.fromPrivateKey(node.privateKey))
        } finally {
            node.zeroize()
        }
    }
}

/**
 * A deposit address's Ethereum key, lent by [DepositKeys.withKey]. It signs only what the Deposits contract takes:
 * never delegate it (EIP-7702) or use its address for anything else, since the relayer refuses an address with code.
 */
class DepositKey internal constructor(
    private val key: EvmKey,
) {
    val address: Address get() = key.address

    /**
     * `r ‖ s ‖ v` over a 32-byte [TokenDomain] or [DepositsDomain] digest, with a low `s` and `v` 27 or 28: what the
     * token and the Deposits contract verify, and what the relayer takes for both a shield and a rescue.
     */
    fun sign(digest: ByteArray): DepositSignature {
        require(digest.size == WORD_BYTES) { "a digest is $WORD_BYTES bytes" }
        val signature = key.signRecoverable(digest)
        // USDC and the Deposits contract refuse a high s, the malleated twin of a valid signature.
        check(signature.s <= HALF_N) { "the signer gave a high s" }
        check(signature.yParity in RECOVERY_IDS) { "the signer gave no y parity" }
        return DepositSignature(
            signature.r.toUnsignedFieldBytes() +
                signature.s.toUnsignedFieldBytes() +
                (ETHEREUM_V_OFFSET + signature.yParity).toByte()
        )
    }

    internal fun zeroize() = key.zeroize()

    override fun toString(): String = "DepositKey(address=$address)"

    private companion object {
        val HALF_N = SECP256K1_N.shiftRight(1)
        val RECOVERY_IDS = setOf<Byte>(0, 1)
        const val ETHEREUM_V_OFFSET = 27
    }
}

/**
 * A deposit key's 65-byte signature, `r ‖ s ‖ v`. It authorizes moving the address's money: never show or log it, so
 * [toString] tells nothing of it.
 */
class DepositSignature internal constructor(
    private val value: ByteArray,
) {
    init {
        require(value.size == SIZE) { "a signature is $SIZE bytes" }
    }

    val bytes: ByteArray get() = value.copyOf()

    /** `0x` and 130 hex digits: the `signature` of a shield or rescue request. */
    val hex: String get() = "0x" + value.toHex()

    override fun equals(other: Any?): Boolean = other is DepositSignature && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "DepositSignature"

    companion object {
        const val SIZE: Int = 65
    }
}

private fun requireReachable(
    account: Int,
    index: Int
) {
    require(account in 0..Int.MAX_VALUE - ACCOUNT_OFFSET) { "a deposit account is from 0 to 2^31 - 1001" }
    require(index >= 0) { "a deposit address's index is from 0 to 2^31 - 1" }
}

private fun master(seed: ByteArray): ExtendedKey {
    require(seed.size in SEED_SIZES) { "a BIP-32 seed is 16, 32 or 64 bytes" }
    return Bip32.master(seed)
}

private fun accountPath(
    mainnet: Boolean,
    account: Int
): List<Int> = listOf(PURPOSE or HARDENED, coinType(mainnet) or HARDENED, (ACCOUNT_OFFSET + account) or HARDENED)

private fun coinType(mainnet: Boolean): Int = if (mainnet) ETHEREUM_COIN_TYPE else TESTNET_COIN_TYPE

private fun ExtendedKey.copy() = ExtendedKey(privateKey.copyOf(), chainCode.copyOf())

private fun addressOf(privateKey: ByteArray): Address {
    val key = EvmKeyDerivation.fromPrivateKey(privateKey)
    return try {
        key.address
    } finally {
        key.zeroize()
    }
}

internal const val WORD_BYTES = 32
private const val PURPOSE = 44
private const val ETHEREUM_COIN_TYPE = 60
private const val TESTNET_COIN_TYPE = 1
private const val EXTERNAL_CHAIN = 0

// The seeds zecSwap's BIP-32 crate takes: 128, 256 or 512 bits. A BIP-39 seed is 512.
private const val SEED_128_BITS = 16
private const val SEED_256_BITS = 32
private const val SEED_512_BITS = 64
private val SEED_SIZES = setOf(SEED_128_BITS, SEED_256_BITS, SEED_512_BITS)
