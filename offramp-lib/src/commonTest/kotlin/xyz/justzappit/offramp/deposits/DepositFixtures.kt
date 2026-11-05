// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.deposits

import kotlinx.serialization.json.Json
import xyz.justzappit.evm.abi.keccak256
import xyz.justzappit.evm.deposits.DepositKey
import xyz.justzappit.evm.deposits.DepositKeys
import xyz.justzappit.evm.deposits.DepositNote
import xyz.justzappit.evm.deposits.TokenDomain
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.math.bigIntegerZero
import xyz.justzappit.evm.math.minus
import xyz.justzappit.evm.rpc.TransactionStatus
import xyz.justzappit.evm.signer.EcdsaSigner
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.types.ChainId
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.offramp.atomicswap.RelayerTerms

/** Sepolia's deposits, as zapp-android pins them and the relayer named them on 2026-10-23. */
internal val SEPOLIA_PIN =
    DepositsPin(
        chainId = ChainId.ETHEREUM_SEPOLIA,
        contract = Address.parse("0x58324e9a866c9690aca08169d3c67630e84c9019"),
        token = Address.parse("0x64c4a5cb09fe0b706d4cf24dc0a45fff275d727c"),
        relayer = Address.parse("0xedc4c88a3f6ae8130a2c18babbabd8100cc1a446"),
    )

/** Ethereum's. */
internal val MAINNET_PIN =
    DepositsPin(
        chainId = ChainId.ETHEREUM_MAINNET,
        contract = Address.parse("0xebc7508e20bd4467491ee7e7589d5c7d4c5e8cb6"),
        token = Address.parse("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"),
        relayer = Address.parse("0x6ae40b34c32d3feaa0eef53d7e4724160b7a2135"),
    )

/** The testnet relayer's `GET /v1/terms`, as it answered on 2026-10-23. */
internal const val SEPOLIA_TERMS =
    """{"relayer":"0xd9633572041886fa7584a2e12f36c8c7f1126412","chainId":11155111,
    "contract":"0x459b0a69e0f736666b66a3ffe0fc17c11f05980d","contractKind":"windows","minWindow":1800,
    "lateWindow":3600,"fee":"100000","feeExpiresAt":1792803160,
    "deposits":{"contract":"0x58324e9a866c9690aca08169d3c67630e84c9019",
    "relayer":"0xedc4c88a3f6ae8130a2c18babbabd8100cc1a446","token":"0x64c4a5cb09fe0b706d4cf24dc0a45fff275d727c",
    "fee":"100000","feeBps":10,"feeExpiresAt":1792800160,"minValue":"2000000","maxValidity":86400,
    "maxGasPriceWei":"50000000000"}}"""

/** The mainnet relayer's, the same day. */
internal const val MAINNET_TERMS =
    """{"relayer":"0xcbcb5f57c79c88a0e4bb450289de2088958a017f","chainId":1,
    "contract":"0xeb3a00bcb6c9ff626fe57a5dbbdcf01730e7898f","contractKind":"windows","minWindow":3600,
    "lateWindow":86400,"fee":"500000","feeExpiresAt":1792803159,
    "deposits":{"contract":"0xebc7508e20bd4467491ee7e7589d5c7d4c5e8cb6",
    "relayer":"0x6ae40b34c32d3feaa0eef53d7e4724160b7a2135","token":"0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48",
    "fee":"105554","feeBps":10,"feeExpiresAt":1792800159,"minValue":"1000000","maxValidity":86400,
    "maxGasPriceWei":"20000000000"}}"""

internal val termsJson = Json { ignoreUnknownKeys = true }

internal fun relayerTerms(json: String): RelayerTerms = termsJson.decodeFromString(RelayerTerms.serializer(), json)

/** The testnet terms, with the fee before its percentage as [fee], expiring at [feeExpiresAt]. */
internal fun sepoliaTerms(
    fee: Long = 100_000,
    feeExpiresAt: Long = NOW + 86_400,
    minValue: Long = 2_000_000,
): RelayerTerms =
    relayerTerms(
        SEPOLIA_TERMS
            .replace(""""fee":"100000","feeBps"""", """"fee":"$fee","feeBps"""")
            .replace("1792800160", feeExpiresAt.toString())
            .replace(""""minValue":"2000000"""", """"minValue":"$minValue""""),
    )

/** A wallet's seed: the deposit vectors' first, with the testnet's coin type. */
internal val SEED =
    (
        "9dfc3c64c2f8bede1533b6a79f8570e5943e0b8fd1cf77107adf7b72cef42185" +
            "d564a3aee24cab43f80e3c4538087d70fc824eabbad596a23c97b6ee8322ccc0"
    ).hexToBytes()

internal const val NOW = 1_792_800_000L

internal fun units(value: Long): BigInteger = bigIntegerValueOf(value)

/** The keys of [SEED] on testnet, counting how many windows were derived. */
internal class FakeKeys : DepositKeyring {
    var derived = 0

    override suspend fun addresses(indices: IntRange): List<Address> {
        derived += 1
        return indices.map(::address)
    }

    override suspend fun <T> withKey(
        index: Int,
        block: (DepositKey) -> T,
    ): T = DepositKeys.withKey(SEED, false, 0, index, block)

    fun address(index: Int): Address = WINDOW[index]

    fun index(address: Address): Int = WINDOW.indexOf(address).also { check(it >= 0) { "not a deposit address" } }

    private companion object {
        // Derived once for every test: a scan reads up to 50 windows.
        val WINDOW: List<Address> by lazy { DepositKeys.addresses(SEED, false, 0, 0 until 50 * GAP) }
    }
}

/** Notes as the seed would build them, each kind, address and number its own, counting how many were built. */
internal class FakeNotes : DepositNotes {
    var made = 0
    var failure: Throwable? = null

    override suspend fun shield(
        index: Int,
        validBefore: Long,
    ): DepositNote = built(FakeNotes.shield(index, validBefore))

    override suspend fun rescue(
        index: Int,
        nonce: Long,
    ): DepositNote = built(FakeNotes.rescue(index, nonce))

    private fun built(note: DepositNote): DepositNote {
        failure?.let { throw it }
        made += 1
        return note
    }

    companion object {
        /** The note address [index]'s shield valid before [validBefore] pays. */
        fun shield(
            index: Int,
            validBefore: Long,
        ) = note(SHIELD, index, validBefore)

        /** The note address [index]'s rescue with the contract's [nonce] pays. */
        fun rescue(
            index: Int,
            nonce: Long,
        ) = note(RESCUE, index, nonce)

        private const val SHIELD = 0
        private const val RESCUE = 1

        private fun note(
            kind: Int,
            index: Int,
            number: Long,
        ) = DepositNote(
            word(kind, index, number, 1),
            List(3) { word(kind, index, number, 2 + it) },
            word(kind, index, number, 5),
        )

        // The kind, the address, the number's eight bytes, then the word's place in the note.
        private fun word(
            kind: Int,
            index: Int,
            number: Long,
            part: Int,
        ) = ByteArray(32) { at ->
            when (at) {
                0 -> kind.toByte()
                1 -> index.toByte()
                in 2..9 -> (number shr (8 * (at - 2))).toByte()
                else -> part.toByte()
            }
        }
    }
}

/** A store in memory: a new record goes first, a kept one stays where it was. */
internal class FakeStore : DepositStore {
    val kept = mutableListOf<DepositRecord>()
    var lastScan: DepositScan? = null
    var writes = 0

    override suspend fun records(): List<DepositRecord> = kept.toList()

    override suspend fun keep(records: List<DepositRecord>) {
        writes += 1
        records.forEach { record ->
            val at = kept.indexOfFirst { it.id == record.id }
            if (at >= 0) kept[at] = record else kept.add(0, record)
        }
    }

    override suspend fun scan(): DepositScan? = lastScan

    override suspend fun keepScan(scan: DepositScan) {
        lastScan = scan
    }

    fun record(id: String) = kept.single { it.id == id }
}

/** A chain whose addresses hold what [balances] says, and whose transactions settle as told. */
internal class FakeChain(
    private val keys: FakeKeys,
) : DepositsChainReader {
    val balances = mutableMapOf<Int, Long>()
    val deposited = mutableSetOf<Int>()
    val vaults = mutableMapOf<Int, Long>()
    val rescueNonces = mutableMapOf<Int, Long>()
    var isAuthorizationUsed: (Address, ByteArray) -> Boolean = { _, _ -> false }
    val statuses = mutableMapOf<TxHash, TransactionStatus>()
    val events = mutableMapOf<TxHash, List<DepositEvent>>()
    var isReadable = true
    var domain = TokenDomain("ZecSwap Test USD", "2", SEPOLIA_PIN.chainId, SEPOLIA_PIN.token)
    var isDomainPinned = true
    var scans = 0

    override suspend fun requireChain() {
        check(isReadable) { "the node is down" }
    }

    override suspend fun uses(owners: List<Address>): List<DepositAddressUse> {
        check(isReadable) { "the node is down" }
        scans += 1
        return owners.map { owner ->
            val index = index(owner)
            DepositAddressUse(units(balances[index] ?: 0), index in deposited)
        }
    }

    override suspend fun tokenDomain(): TokenDomain {
        if (!isDomainPinned) throw DepositsException(DepositProblem.WRONG_DEPLOYMENT, "not the pinned token")
        return domain
    }

    override suspend fun vaultBalances(owners: List<Address>): List<BigInteger> =
        owners.map { units(vaults[index(it)] ?: 0) }

    override suspend fun rescueNonce(owner: Address): Long = rescueNonces[index(owner)] ?: 0

    override suspend fun authorizationUsed(
        owner: Address,
        nonce: ByteArray,
    ): Boolean = isAuthorizationUsed(owner, nonce)

    override suspend fun status(transaction: TxHash): TransactionStatus =
        statuses[transaction] ?: TransactionStatus.UNKNOWN

    override suspend fun events(transaction: TxHash): List<DepositEvent> = events[transaction].orEmpty()

    /** [record]'s own event, as the pinned contract emits it when its deposit lands. */
    fun landed(
        transaction: TxHash,
        record: DepositRecord,
        shielded: Long = (record.value.micros - record.fee).toLong(),
    ) {
        statuses[transaction] = TransactionStatus.CONFIRMED
        events[transaction] = listOf(event(record, shielded = units(shielded)))
    }

    private fun index(owner: Address): Int = keys.index(owner)
}

/** The event the pinned contract emits for [record]'s deposit, as its fields could be. */
internal fun event(
    record: DepositRecord,
    shielded: BigInteger = record.value.micros - record.fee,
    from: Address = record.from,
    note: xyz.justzappit.offramp.atomicswap.NoteCommitment = record.commitment,
) = DepositEvent(record.kind, from, shielded, record.fee, SEPOLIA_PIN.relayer, note)

/** A relayer whose terms are [terms] and which answers each post with the next of [answers]. */
internal class FakeRelayer(
    var terms: () -> RelayerTerms = { sepoliaTerms() },
) : DepositsRelayer {
    val answers = ArrayDeque<DepositAnswer>()
    val shields = mutableListOf<ShieldDeposit>()
    val rescues = mutableListOf<RescueDeposit>()

    /** What the store held of each request as it was posted. */
    var onPost: (Any) -> Unit = {}

    override suspend fun terms(): RelayerTerms = terms.invoke()

    override suspend fun shield(request: ShieldDeposit): DepositAnswer {
        onPost(request)
        shields += request
        return answers.removeFirstOrNull() ?: DepositAnswer.Retry("no answer", null, isWaiting = true)
    }

    override suspend fun rescue(request: RescueDeposit): DepositAnswer {
        onPost(request)
        rescues += request
        return answers.removeFirstOrNull() ?: DepositAnswer.Retry("no answer", null, isWaiting = true)
    }
}

internal fun tx(byte: Int) = TxHash(ByteArray(32) { byte.toByte() })

/** Who signed [digest] with the 65-byte [signature], as `ecrecover` finds it, if `s` is low and `v` 27 or 28. */
internal fun signer(
    digest: ByteArray,
    signature: String,
): Address? {
    val bytes = signature.hexToBytes()
    val r = BigInteger(1, bytes.copyOfRange(0, 32))
    val s = BigInteger(1, bytes.copyOfRange(32, 64))
    val v = bytes[64].toInt()
    val half = BigInteger("7fffffffffffffffffffffffffffffff5d576e7357a4501ddfe92f46681b20a0", 16)
    val accepted = (v == 27 || v == 28) && s <= half && s > bigIntegerZero
    val point = if (accepted) EcdsaSigner.recoverPublicKeyBytes(v - 27, r, s, digest) else null
    return point?.let {
        val hash = keccak256(it.copyOfRange(1, it.size))
        Address.fromBytes(hash.copyOfRange(hash.size - 20, hash.size))
    }
}
