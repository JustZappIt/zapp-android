// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.deposits

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.justzappit.evm.math.BigInteger
import xyz.justzappit.evm.types.Address
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.time.Duration

/** What a pass leaves: the terms as read, the addresses, every deposit kept, and when to look again. */
data class DepositPass(
    /** What the relayer's terms say of deposits here; none when they didn't read. */
    val terms: DepositTermsRead?,
    /** This pass's scan, or the last one kept when the chain didn't read. */
    val scan: DepositScan?,
    val isScanned: Boolean,
    /** Every deposit kept, newest first. */
    val records: List<DepositRecord>,
    /** When to look again; none while nothing is posted or due. */
    val nextPoll: Duration?,
    /** An address due a deposit wasn't signed for, since the relayer's fee is over this wallet's cap. */
    val isFeeOverCap: Boolean,
)

/** A vault holding more than a rescue's fee: what Railgun's screening sent back to address [index]. */
data class DepositRescuable(
    val index: Int,
    val held: BigInteger,
    val fee: BigInteger,
)

/**
 * A wallet's deposits: public tokens sent to deposit addresses derived from its seed, shielded into its own Railgun
 * balance through the relayer, which pays the gas for the fee each authorization commits to. It plays zecswap-client's
 * `Depositor` as a kept state machine, as Veylor's passes do. Every request is kept before it's posted, so a pass
 * stopped at any point leaves the store consistent, and the next posts the kept bytes again.
 *
 * Never log a request, a record, an address or a note: they carry signatures and link the wallet. Logs carry indices.
 */
class Depositor(
    private val pin: DepositsPin,
    private val relayer: DepositsRelayer,
    private val chain: DepositsChainReader,
    private val keys: DepositKeyring,
    notes: DepositNotes,
    private val store: DepositStore,
    private val now: () -> Long,
    private val log: (String) -> Unit = {},
) {
    // Passes and rescues take turns.
    private val lock = Mutex()
    private val scanner = DepositScanner(keys, chain, now)
    private val signing = DepositSigning(pin, chain, keys, notes, now)
    private val posting = DepositPosting(pin, relayer, chain, store, signing, now, log)
    private val following = DepositFollowing(store, chain, signing, posting, now, log)

    /**
     * One pass: scan the addresses, move on every request kept and not finished, then, with [signNew], sign for each
     * address that holds the minimum and has none under way, keep it and post it. Without [signNew], as while Shielded
     * USD is switched off, deposits under way still go on. A step that fails is logged and the pass goes on.
     */
    suspend fun pass(signNew: Boolean): DepositPass =
        lock.withLock {
            val isChainRead = attempt(log, "the deposits chain didn't read") { chain.requireChain() } != null
            val terms = attempt(log, "the relayer's deposit terms didn't read") { relayer.terms().depositTerms(pin) }
            val pinned = (terms as? DepositTermsRead.Pinned)?.terms
            val scan =
                if (isChainRead) {
                    attempt(log, "the deposit addresses didn't read") { scanner.scan().also { store.keepScan(it) } }
                } else {
                    null
                }
            val context = PassContext(pinned, mayPost = terms == null || pinned != null, scan)
            val underWay = advanceKept(context, isChainRead)
            val signed =
                if (signNew && scan != null && pinned != null) signDue(scan, pinned, underWay, context) else SignedDue()
            report(terms, context, signNew, signed)
        }

    /** The used addresses whose vaults hold more than a rescue's fee: what Railgun's screening sent back to them. */
    suspend fun rescuable(): List<DepositRescuable> {
        val fee = DepositFees.rescue(pinnedTerms())
        val deposited =
            store
                .scan()
                ?.used
                .orEmpty()
                .filter { it.deposited }
        if (fee == null || deposited.isEmpty()) return emptyList()
        chain.requireChain()
        val held = chain.vaultBalances(deposited.map { scanner.address(it.index) })
        return deposited
            .zip(held)
            .filter { (_, vault) -> vault > fee }
            .map { (address, vault) -> DepositRescuable(address.index, vault, fee) }
    }

    /**
     * Shields again what Railgun sent back to address [index]'s vault, the user's step behind a confirmation: signed
     * with the contract's nonce for it, kept, then posted, paying the terms' fee alone. [DepositsException] while the
     * vault holds no more than that fee, or a rescue of the address is on its way.
     */
    suspend fun rescue(index: Int): DepositRecord =
        lock.withLock {
            chain.requireChain()
            val terms = pinnedTerms()
            val fee =
                DepositFees.rescue(terms)
                    ?: throw DepositsException(DepositProblem.FEE_OVER_CAP, "the relayer's rescue fee is over the cap")
            val from = scanner.address(index)
            val held = chain.vaultBalances(listOf(from)).single()
            val isUnderWay =
                store.records().any { it.kind == DepositKind.RESCUE && it.index == index && !it.state.isFinished }
            if (isUnderWay || held <= fee) {
                throw DepositsException(DepositProblem.NOTHING_TO_RESCUE, "nothing to rescue at this address now")
            }
            val record = signing.rescue(index, held, fee, chain.rescueNonce(from), terms)
            store.keep(listOf(record))
            posting.postKept(record, PassContext(terms, mayPost = true, scan = null))
            store.records().first { it.id == record.id }
        }

    /** Every deposit address given out, or that could have been: up to the last scan's end, one window at least. */
    suspend fun ownAddresses(): Set<Address> =
        keys.addresses(0 until maxOf(1, store.scan()?.windows ?: 1) * GAP).toSet()

    /** Deposit address [index]. */
    suspend fun address(index: Int): Address = scanner.address(index)

    private suspend fun pinnedTerms(): DepositTerms =
        (relayer.terms().depositTerms(pin) as? DepositTermsRead.Pinned)?.terms
            ?: throw DepositsException(DepositProblem.NO_TERMS, "the relayer takes no deposits for this deployment")

    // Every request kept and not finished, oldest first, and the indices they're for. An address with one under way
    // isn't signed for again in this pass: the balance scanned may be the one that request just moved. One kept for
    // another wallet's address, which a wipe would have cleared, is left alone.
    private suspend fun advanceKept(
        context: PassContext,
        isChainRead: Boolean,
    ): Set<Int> {
        val kept = store.records().filterNot { it.state.isFinished }
        kept.asReversed().forEach { record ->
            attempt(log, "the ${record.kind.name.lowercase()} of address ${record.index} didn't move on") {
                if (scanner.isOwn(record)) following.advance(record, context, isChainRead)
            }
        }
        return kept.map { it.index }.toSet()
    }

    // Each used address due a deposit under [terms], signed for, kept, then posted.
    private suspend fun signDue(
        scan: DepositScan,
        terms: DepositTerms,
        underWay: Set<Int>,
        context: PassContext,
    ): SignedDue {
        val kept = store.records()
        val failures =
            scan.used
                .filter { it.index !in underWay && due(kept, it, terms, now()) }
                .mapNotNull { address ->
                    failure(log, "address ${address.index} wasn't signed for") {
                        val record = signing.shield(address.index, address.balance.micros, terms, afterRefusal = false)
                        store.keep(listOf(record))
                        if (context.mayPost) posting.postKept(record, context)
                    }.exceptionOrNull()
                }
        return SignedDue(
            isUnsigned = failures.isNotEmpty(),
            isFeeOverCap = failures.any { (it as? DepositsException)?.problem == DepositProblem.FEE_OVER_CAP },
        )
    }

    // An address that holds enough but wasn't signed for, or terms that didn't read for now (never ones that take no
    // deposits here), bring the next pass in a minute.
    private suspend fun report(
        terms: DepositTermsRead?,
        context: PassContext,
        signNew: Boolean,
        signed: SignedDue,
    ): DepositPass {
        val records = store.records()
        val view = context.scan ?: store.scan()
        val now = now()
        val held = view?.used.orEmpty()
        val isDue =
            when (val pinned = context.terms) {
                null -> terms == null && held.any { it.balance.micros.signum() > 0 }
                else -> held.any { due(records, it, pinned, now) }
            }
        val retry = SIGN_RETRY.takeIf { signNew && (signed.isUnsigned || isDue) }
        return DepositPass(
            terms = terms,
            scan = view,
            isScanned = context.scan != null,
            records = records,
            nextPoll = nextPoll(records, now) ?: retry,
            isFeeOverCap = signed.isFeeOverCap,
        )
    }

    private class SignedDue(
        val isUnsigned: Boolean = false,
        val isFeeOverCap: Boolean = false,
    )
}

/** What a pass knows while it moves one request on. */
internal class PassContext(
    /** The relayer's terms, held to the pin; none when they didn't read or take no deposits here. */
    val terms: DepositTerms?,
    /** The relayer's terms are the pin's, or didn't read: kept bytes may go to it, never to another deployment's. */
    val mayPost: Boolean,
    /** This pass's scan; none when the chain didn't read. */
    val scan: DepositScan?,
) {
    /** What address [index] holds as this pass read it, once that's at least the minimum under [terms]. */
    fun signable(index: Int): BigInteger? {
        val minValue = terms?.minValue ?: return null
        return scan?.balance(index)?.takeIf { it >= minValue }
    }
}

/** The deposit addresses: derived from the seed, and read from the chain a window at a time. */
internal class DepositScanner(
    private val keys: DepositKeyring,
    private val chain: DepositsChainReader,
    private val now: () -> Long,
) {
    /**
     * Every used address, read [GAP] at a time from index 0 until a window holds none used, as a wallet restored from
     * its seed finds them: an old address that gets money later is found and shielded to a note of its own. A node
     * claiming every address is used never keeps a scan going past [MAX_WINDOWS].
     */
    suspend fun scan(): DepositScan {
        val used = mutableListOf<ScannedAddress>()
        var windows = 0
        do {
            val first = windows * GAP
            val owners = keys.addresses(first until first + GAP)
            val uses = chain.uses(owners)
            check(uses.size == owners.size) { "a scan answered another count" }
            val found =
                uses.mapIndexedNotNull { offset, use ->
                    ScannedAddress(first + offset, Usdc6(use.balance), use.deposited).takeIf { use.isUsed }
                }
            used += found
            windows += 1
        } while (found.isNotEmpty() && windows < MAX_WINDOWS)
        return DepositScan(now(), showing(used), used, windows)
    }

    suspend fun address(index: Int): Address = keys.addresses(index..index).single()

    /** Whether [record] is for this wallet's address at its index. */
    suspend fun isOwn(record: DepositRecord): Boolean = address(record.index) == record.from

    private companion object {
        const val MAX_WINDOWS = 50
    }
}
