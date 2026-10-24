// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.deposits

import xyz.justzappit.evm.abi.AbiAddress
import xyz.justzappit.evm.abi.AbiBytes32
import xyz.justzappit.evm.abi.AbiDecoder
import xyz.justzappit.evm.abi.AbiEncoder
import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.deposits.TokenDomain
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.toNonNegativeLongExact
import xyz.justzappit.evm.multicall.Multicall3Call
import xyz.justzappit.evm.multicall.Multicall3Result
import xyz.justzappit.evm.multicall.aggregate3
import xyz.justzappit.evm.rpc.BaseRpcClient
import xyz.justzappit.evm.rpc.EvmLog
import xyz.justzappit.evm.rpc.TransactionStatus
import xyz.justzappit.evm.rpc.transactionStatus
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.atomicswap.NoteCommitment

/** What one deposit address holds of the token, and whether it has deposited: it's used if either. */
data class DepositAddressUse(
    val balance: BigInteger,
    val deposited: Boolean,
) {
    val isUsed: Boolean get() = balance.signum() > 0 || deposited
}

/** A deposit or rescue the pinned Deposits contract recorded: whose, what it shielded before Railgun's fee, to what. */
data class DepositEvent(
    val kind: DepositKind,
    val from: Address,
    /** The event's `value`: what the vault shielded, before Railgun's own fee. */
    val shielded: BigInteger,
    val fee: BigInteger,
    val relayer: Address,
    /** `noteCommitment(npk, ciphertext)` of the note it shielded to. */
    val note: NoteCommitment,
)

/** Why deposits can't go ahead now. */
enum class DepositProblem {
    /** The node, the Deposits contract or its token isn't the pinned deployment's. */
    WRONG_DEPLOYMENT,

    /** The relayer's terms take no deposits for the pinned deployment, or didn't read. */
    NO_TERMS,

    /** The relayer's fee is over this wallet's cap, or leaves nothing to shield. */
    FEE_OVER_CAP,

    /** The vault holds no more than a rescue's fee, or a rescue of the address is on its way. */
    NOTHING_TO_RESCUE,
}

class DepositsException(
    val problem: DepositProblem,
    message: String,
) : IllegalStateException(message)

/** What deposits read of the chain. Nothing here sends: the relayer does. */
interface DepositsChainReader {
    /** Throws [DepositsException] unless the node serves the pinned chain. */
    suspend fun requireChain()

    /** What each of [owners] holds and whether it deposited, in one read as of one block. */
    suspend fun uses(owners: List<Address>): List<DepositAddressUse>

    /**
     * The token's EIP-712 domain from its own `name()` and `version()`, once the Deposits contract's `TOKEN()` is the
     * pinned token and the token's `DOMAIN_SEPARATOR()` is that domain's; [DepositsException] otherwise.
     */
    suspend fun tokenDomain(): TokenDomain

    /** What each of [owners]' vaults holds: what Railgun's screening sent back to them. */
    suspend fun vaultBalances(owners: List<Address>): List<BigInteger>

    /** The nonce [owner]'s next rescue signs. */
    suspend fun rescueNonce(owner: Address): Long

    /** Whether [owner] used the EIP-3009 [nonce]: only the Deposits contract can, for that nonce's note and fee. */
    suspend fun authorizationUsed(
        owner: Address,
        nonce: ByteArray,
    ): Boolean

    suspend fun status(transaction: TxHash): TransactionStatus

    /** The deposits and rescues the pinned contract recorded in [transaction]; none while it's unmined. */
    suspend fun events(transaction: TxHash): List<DepositEvent>
}

/** [pin]'s Deposits contract and token over JSON-RPC. */
class DepositsChain(
    private val rpc: BaseRpcClient,
    private val pin: DepositsPin,
) : DepositsChainReader {
    override suspend fun requireChain() {
        if (rpc.ethChainId() != pin.chainId) {
            throw DepositsException(DepositProblem.WRONG_DEPLOYMENT, "the node serves another chain")
        }
    }

    override suspend fun uses(owners: List<Address>): List<DepositAddressUse> {
        if (owners.isEmpty()) return emptyList()
        val calls = owners.flatMap { listOf(call(pin.token, BALANCE_OF, it), call(pin.contract, DEPOSITED, it)) }
        return rpc.aggregate3(calls).chunked(2).map { (balance, deposited) ->
            DepositAddressUse(balance.uint(), deposited.flag())
        }
    }

    override suspend fun tokenDomain(): TokenDomain {
        val asked =
            listOf(
                Multicall3Call(pin.contract, AbiEncoder.encodeFunctionCall("TOKEN()", emptyList())),
                Multicall3Call(pin.token, AbiEncoder.encodeFunctionCall("name()", emptyList())),
                Multicall3Call(pin.token, AbiEncoder.encodeFunctionCall("version()", emptyList())),
                Multicall3Call(pin.token, AbiEncoder.encodeFunctionCall("DOMAIN_SEPARATOR()", emptyList())),
            )
        val answers = rpc.aggregate3(asked)
        val domain = TokenDomain(answers[NAME].string(), answers[VERSION].string(), pin.chainId, pin.token)
        val isPinned =
            answers[TOKEN].words(1).address(0) == pin.token &&
                answers[SEPARATOR].words(1).word(0).contentEquals(domain.separator())
        if (!isPinned) {
            throw DepositsException(DepositProblem.WRONG_DEPLOYMENT, "the token isn't the pinned one, or its domain")
        }
        return domain
    }

    override suspend fun vaultBalances(owners: List<Address>): List<BigInteger> {
        if (owners.isEmpty()) return emptyList()
        val vaults = rpc.aggregate3(owners.map { call(pin.contract, VAULT_OF, it) }).map { it.words(1).address(0) }
        return rpc.aggregate3(vaults.map { call(pin.token, BALANCE_OF, it) }).map { it.uint() }
    }

    override suspend fun rescueNonce(owner: Address): Long =
        AbiDecoder(rpc.ethCall(pin.contract, calldata(RESCUE_NONCES, owner)))
            .also { it.requireWords(1) }
            .uint(0)
            .toNonNegativeLongExact()

    override suspend fun authorizationUsed(
        owner: Address,
        nonce: ByteArray,
    ): Boolean {
        val call =
            AbiEncoder.encodeFunctionCall(AUTHORIZATION_STATE, listOf(AbiAddress(owner), AbiBytes32(nonce.copyOf())))
        return AbiDecoder(rpc.ethCall(pin.token, call)).also { it.requireWords(1) }.uint8(0) != 0
    }

    override suspend fun status(transaction: TxHash): TransactionStatus = rpc.transactionStatus(transaction, 1)

    override suspend fun events(transaction: TxHash): List<DepositEvent> =
        rpc
            .ethGetTransactionReceipt(transaction)
            ?.logs
            .orEmpty()
            .mapNotNull { it.depositEvent() }

    // The pinned contract's DepositShielded or Rescued: from indexed, then value, fee, relayer and the note.
    private fun EvmLog.depositEvent(): DepositEvent? {
        val kind = EVENTS[topics.firstOrNull()?.lowercase()]
        val data = data.takeIf { it.length == 2 + EVENT_WORDS * AbiDecoder.WORD * 2 }?.hexToBytes()
        val isOurs = !removed && address.equals(pin.contract.lowercaseHex, ignoreCase = true) && topics.size == 2
        if (kind == null || data == null || !isOurs) return null
        val words = AbiDecoder(data)
        return DepositEvent(
            kind = kind,
            from = Address.fromBytes(topics[1].hexToBytes().copyOfRange(ADDRESS_FROM, AbiDecoder.WORD)),
            shielded = words.uint(0),
            fee = words.uint(1),
            relayer = words.address(2),
            note = NoteCommitment.of(words.word(EVENT_WORDS - 1)),
        )
    }

    private companion object {
        const val BALANCE_OF = "balanceOf(address)"
        const val DEPOSITED = "deposited(address)"
        const val VAULT_OF = "vaultOf(address)"
        const val RESCUE_NONCES = "rescueNonces(address)"
        const val AUTHORIZATION_STATE = "authorizationState(address,bytes32)"
        const val EVENT_WORDS = 4

        // The token-domain read's answers, in the order it asks.
        const val TOKEN = 0
        const val NAME = 1
        const val VERSION = 2
        const val SEPARATOR = 3
        const val ADDRESS_FROM = AbiDecoder.WORD - Address.LEN_BYTES

        val EVENTS = mapOf(DEPOSIT_SHIELDED_TOPIC to DepositKind.SHIELD, RESCUED_TOPIC to DepositKind.RESCUE)

        fun calldata(
            signature: String,
            owner: Address,
        ): ByteArray = AbiEncoder.encodeFunctionCall(signature, listOf(AbiAddress(owner)))

        fun call(
            target: Address,
            signature: String,
            owner: Address,
        ) = Multicall3Call(target, calldata(signature, owner))
    }
}

/** `DepositShielded(address indexed from, uint256 value, uint256 fee, address relayer, bytes32 note)`'s topic. */
internal val DEPOSIT_SHIELDED_TOPIC: String = topic("DepositShielded(address,uint256,uint256,address,bytes32)")

/** `Rescued`'s topic, of the same fields. */
internal val RESCUED_TOPIC: String = topic("Rescued(address,uint256,uint256,address,bytes32)")

private fun topic(event: String): String = "0x" + keccak256(event.encodeToByteArray()).toHex()

// A `bool`, one word.
private fun Multicall3Result.flag(): Boolean = words(1).uint8(0) != 0

// A `string`, as name() and version() answer it: the offset of its length, then its bytes.
private fun Multicall3Result.string(): String {
    val words = words(1)
    return words.dynamicStringAt(words.uint(0).toNonNegativeLongExact().toInt())
}
