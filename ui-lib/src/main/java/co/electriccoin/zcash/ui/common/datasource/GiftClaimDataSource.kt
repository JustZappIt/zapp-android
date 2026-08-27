// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.datasource

import android.content.Context
import cash.z.ecc.android.sdk.CloseableSynchronizer
import cash.z.ecc.android.sdk.SdkSynchronizer
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.exception.InitializeException
import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.model.AccountBalance
import cash.z.ecc.android.sdk.model.AccountCreateSetup
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionState
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.sdk.extension.ZERO
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.provider.GiftKeyProvider
import co.electriccoin.zcash.ui.screen.gift.model.GiftLinkPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * How far a claim has got, for a progress screen that would otherwise sit on a blank spinner.
 *
 * [fraction] must come from the SDK, never from these heights: `Synchronizer.new` snaps the
 * birthday down (54,000 blocks, measured on testnet), so a height-derived fraction is negative for
 * the whole scan and then snaps to 100%. §7.2.
 */
data class GiftClaimProgress(
    val status: Synchronizer.Status,
    val fraction: Float,
    val scannedHeight: Long?,
    val tipHeight: Long?,
)

/**
 * Confirmations a shielded note needs before it can be spent. A gift note is untrusted, so
 * `ConfirmationsPolicy::default()` gives it 10. Mirrored from Rust — no Android API exposes it (§4).
 */
const val REQUIRED_CONFIRMATIONS = 10

/** The SDK's own spelling of its no-backup subdirectory — `Files.NO_BACKUP_SUBDIRECTORY`, one `c`. */
private const val SDK_NO_BACKUP_SUBDIRECTORY = "co.electricoin.zcash"

/** SDK-internal layout, so it lives in one tested function rather than inline. */
internal fun giftWalletFileNames(alias: String, networkName: String): List<String> {
    val prefix = "${alias}_${networkName}_"
    return listOf(
        "${prefix}fs_cache",
        "${prefix}data.sqlite3",
        "${prefix}data.sqlite3-journal",
        "${prefix}data.sqlite3-wal",
        "${prefix}data.sqlite3-shm",
    )
}

/**
 * [Synchronizer.close] returns before shutdown finishes and the database files stay open until it
 * does, so deleting them in that window races the block-cache teardown and the WAL checkpoint.
 */
private suspend fun CloseableSynchronizer.closeAndAwait() {
    if (this is SdkSynchronizer) closeFlow().first() else close()
}

// Every shielded pool: the card is funded to a unified address and the *sender's* wallet picks
// which, so reading one pool would report a good card as empty the moment that changed.
private fun AccountBalance.shieldedAvailable() = sapling.available + orchard.available + ironwood.available

private fun AccountBalance.shieldedTotal() = sapling.total + orchard.total + ironwood.total

/** The card's server could not be reached at all, so nothing was learned about the card. */
class GiftCardUnreachableException : RuntimeException("Card wallet could not reach its server")

/** The isolated synchronizer was stopped and can never reach SYNCED. */
class GiftCardSynchronizerStoppedException : RuntimeException("Card wallet synchronizer stopped")

/** The card's scan stopped advancing, so it will never reach SYNCED on its own. */
class GiftCardScanStalledException : RuntimeException("Card wallet scan stopped advancing")

private val STALL_POLL_INTERVAL = 10.seconds

// Must exceed the SDK's 90s gRPC streaming deadline: nothing advances for the whole of a block-batch
// download, so a shorter window would cut off a batch that is still legitimately downloading.
internal val STALL_TIMEOUT = 6.minutes

internal val STALL_POLL_LIMIT = (STALL_TIMEOUT / STALL_POLL_INTERVAL).toInt()

/**
 * Suspends forever while the card's scan keeps moving, and throws [GiftCardScanStalledException]
 * once it has not moved for [STALL_TIMEOUT].
 */
internal suspend fun failWhenScanStalls(
    scannedHeight: () -> Long?,
    fraction: () -> Float,
): Nothing {
    // Below every real height, so the first one to arrive counts as movement.
    var furthestHeight = scannedHeight() ?: Long.MIN_VALUE
    var furthestFraction = fraction()
    var idlePolls = 0
    while (true) {
        delay(STALL_POLL_INTERVAL)
        val height = scannedHeight() ?: Long.MIN_VALUE
        val current = fraction()
        // Either signal counts: the height only lands per batch, the fraction moves within one.
        // Furthest-ever, not last, so a regressing height is the failing batch restarting.
        if (height > furthestHeight || current > furthestFraction) {
            furthestHeight = maxOf(furthestHeight, height)
            furthestFraction = maxOf(furthestFraction, current)
            idlePolls = 0
        } else if (++idlePolls >= STALL_POLL_LIMIT) {
            Twig.warn { "Gift claim: card wallet scan made no progress for $STALL_TIMEOUT" }
            throw GiftCardScanStalledException()
        }
    }
}

/** What a minted card's own wallet holds right now, and whether it ever held anything. */
data class GiftCardHoldings(
    val available: Zatoshi,
    val total: Zatoshi,
    /**
     * Whether the card's funding transaction — that one, by txid — has mined.
     *
     * "Any mined transaction" is cheaper and wrong: the address is plaintext in the link, so a
     * *transparent* send from anyone mines into this history while leaving [total] at zero —
     * indistinguishable from a collected card, and settling is terminal. False means the funding is
     * still in the mempool, or dropped and possibly yet to mine before it expires.
     */
    val hasFundingArrived: Boolean,
    /** A claim spend at the SDK's full confirmation threshold. */
    val hasFinalClaimSpend: Boolean = false,
    /** A submitted/mined claim that can still expire or reorg. */
    val hasPendingClaimSpend: Boolean = false,
) {
    /** Nothing left in the card wallet. */
    val isEmpty: Boolean get() = total == Zatoshi.ZERO

    /** The funding arrived and a claim spend reached SDK finality. */
    val isCollected: Boolean get() = hasFundingArrived && hasFinalClaimSpend
}

/** Whether recipient-side finality may discard the retry secret and isolated database. */
data class GiftClaimFinalization(
    val canSettle: Boolean,
    val residual: Zatoshi,
)

/** Durable evidence that an outgoing card-wallet spend was submitted by this recipient. */
data class GiftClaimResumeEvidence(
    val claimTxIds: Set<String>,
    val submissionWasAttempted: Boolean,
)

internal enum class GiftOutgoingClaimDisposition {
    NONE,
    RESUME,
    AWAITING_FINALITY,
    ALREADY_CLAIMED,
}

internal fun classifyOutgoingGiftClaim(
    finalTxIds: Set<String>,
    pendingTxIds: Set<String>,
    locallySubmittedTxIds: Set<String>,
): GiftOutgoingClaimDisposition {
    val outgoingTxIds = finalTxIds + pendingTxIds
    if (outgoingTxIds.isEmpty()) return GiftOutgoingClaimDisposition.NONE
    val resumedTxIds = outgoingTxIds intersect locallySubmittedTxIds
    return when {
        (finalTxIds - resumedTxIds).isNotEmpty() -> GiftOutgoingClaimDisposition.ALREADY_CLAIMED
        resumedTxIds.isEmpty() -> GiftOutgoingClaimDisposition.AWAITING_FINALITY
        else -> GiftOutgoingClaimDisposition.RESUME
    }
}

internal fun TransactionOverview.isFinalClaimSpend(amount: Zatoshi): Boolean =
    isSentTransaction && netValue >= amount && transactionState == TransactionState.Confirmed

internal fun TransactionOverview.isPendingClaimSpend(amount: Zatoshi): Boolean =
    isSentTransaction && netValue >= amount && transactionState == TransactionState.Pending

/** What the card's own wallet turned out to hold. */
sealed interface GiftClaimOutcome {
    /** The funds are now in the recipient's wallet. */
    data class Claimed(
        val amount: Zatoshi,
        val txIds: List<String>,
    ) : GiftClaimOutcome

    /**
     * The money is there but not yet spendable — ten confirmations, roughly 12.5 minutes on mainnet.
     * Its own case because calling a perfectly good card empty here would be a lie (§4.1), and
     * [confirmations] lets the wait render as progress rather than a bare "try again".
     */
    data class NotYetSpendable(
        val available: Zatoshi,
        val total: Zatoshi,
        val confirmations: Int?,
        val requiredConfirmations: Int = REQUIRED_CONFIRMATIONS,
    ) : GiftClaimOutcome

    /** Funding has not arrived, or another holder's pending spend is not final enough for a verdict. */
    data object AwaitingFunding : GiftClaimOutcome

    /** A claim spend exists, but this wallet has no durable evidence that it submitted it. */
    data object AlreadyClaimed : GiftClaimOutcome

    /**
     * The card holds its amount but cannot also cover the fee to move it, so no transfer can be
     * proposed over it.
     *
     * Distinct from [NotYetSpendable] because waiting does not fix it. A card minted here is funded
     * with the amount *plus* `FundGiftCardUseCase.CLAIM_FEE_RESERVE` precisely so this cannot
     * happen; reaching it means the card came from something that does not reserve the fee — a peer
     * implementation, or a build from before the reserve — or that ZIP 317 now asks for more than
     * the reserve covers. The funds are untouched and the card's wallet is retained.
     */
    data class Underfunded(
        val available: Zatoshi,
    ) : GiftClaimOutcome

    /** The broadcast did not unambiguously succeed. */
    data class NotBroadcast(
        val result: SubmitResult,
    ) : GiftClaimOutcome
}

/**
 * Runs a gift card's own throwaway wallet, long enough to move its funds into this one.
 *
 * A second, entirely separate [Synchronizer] — never the app's own, which holds the user's real
 * funds and has no business hosting a bearer seed. Running two concurrently was verified on device
 * first: no SQLite contention, per-alias database and block-cache paths, duplicate aliases rejected
 * outright (`docs/gift-cards.md` §7.1).
 */
interface GiftClaimDataSource {
    /**
     * Syncs the card's wallet and moves at least the advertised amount to [recipientAddress].
     * Spendable top-ups are swept so no recoverable funds are discarded with the isolated wallet.
     */
    suspend fun claim(
        payload: GiftLinkPayload,
        cardAddress: String,
        network: ZcashNetwork,
        endpoint: LightWalletEndpoint,
        recipientAddress: String,
        resumeEvidence: GiftClaimResumeEvidence,
        onBeforeSubmit: suspend () -> Unit,
        onProgress: (GiftClaimProgress) -> Unit,
    ): GiftClaimOutcome

    /**
     * Syncs the card's wallet and reports what it holds, spending nothing. The only way to learn
     * whether a card was collected: the note is shielded, so nothing short of scanning with the
     * card's own viewing key can see it spent.
     *
     * [fundingTxid] is required, not optional — see [GiftCardHoldings.hasFundingArrived]. A caller
     * without one has a card that was never funded. [cardAddress] is the card's own address, which
     * the link no longer carries.
     */
    suspend fun inspect(
        payload: GiftLinkPayload,
        cardAddress: String,
        network: ZcashNetwork,
        endpoint: LightWalletEndpoint,
        fundingTxid: String,
        onProgress: (GiftClaimProgress) -> Unit,
    ): GiftCardHoldings

    suspend fun inspectFinalization(
        payload: GiftLinkPayload,
        cardAddress: String,
        network: ZcashNetwork,
        endpoint: LightWalletEndpoint,
    ): GiftClaimFinalization

    suspend fun cleanupFinalizedClaim(
        payload: GiftLinkPayload,
        cardAddress: String,
        network: ZcashNetwork,
    )
}

@Suppress("TooManyFunctions")
internal class GiftClaimDataSourceImpl(
    private val context: Context,
    private val giftKeyProvider: GiftKeyProvider,
) : GiftClaimDataSource {
    private val claimLocks = ConcurrentHashMap<String, Mutex>()

    override suspend fun claim(
        payload: GiftLinkPayload,
        cardAddress: String,
        network: ZcashNetwork,
        endpoint: LightWalletEndpoint,
        recipientAddress: String,
        resumeEvidence: GiftClaimResumeEvidence,
        onBeforeSubmit: suspend () -> Unit,
        onProgress: (GiftClaimProgress) -> Unit,
    ): GiftClaimOutcome {
        val alias = giftAlias(payload.network, cardAddress)
        return lockFor(alias).withLock {
            val synchronizer = open(payload, network, endpoint, alias)
            try {
                claimFrom(
                    synchronizer = synchronizer,
                    payload = payload,
                    recipientAddress = recipientAddress,
                    resumeEvidence = resumeEvidence,
                    onBeforeSubmit = onBeforeSubmit,
                    onProgress = onProgress,
                )
            } finally {
                withContext(NonCancellable) { synchronizer.closeAndAwait() }
            }
        }
    }

    override suspend fun inspect(
        payload: GiftLinkPayload,
        cardAddress: String,
        network: ZcashNetwork,
        endpoint: LightWalletEndpoint,
        fundingTxid: String,
        onProgress: (GiftClaimProgress) -> Unit,
    ): GiftCardHoldings {
        val alias = giftAlias(payload.network, cardAddress)
        return lockFor(alias).withLock {
            val synchronizer = open(payload, network, endpoint, alias)
            val holdings =
                try {
                    awaitReachable(synchronizer)
                    awaitSynced(synchronizer, onProgress)
                    readHoldings(synchronizer, payload, fundingTxid)
                } finally {
                    withContext(NonCancellable) { synchronizer.closeAndAwait() }
                }

            if (holdings.isCollected && holdings.isEmpty) deleteWallet(alias, network)
            holdings
        }
    }

    override suspend fun inspectFinalization(
        payload: GiftLinkPayload,
        cardAddress: String,
        network: ZcashNetwork,
        endpoint: LightWalletEndpoint,
    ): GiftClaimFinalization {
        val alias = giftAlias(payload.network, cardAddress)
        return lockFor(alias).withLock {
            val synchronizer = open(payload, network, endpoint, alias)
            val finalization =
                try {
                    awaitReachable(synchronizer)
                    awaitSynced(synchronizer) {}
                    val account = synchronizer.getAccounts().first()
                    val balance =
                        synchronizer.walletBalances
                            .filterNotNull()
                            .first()
                            .getValue(account.accountUuid)
                    val residual = balance.shieldedTotal()
                    val hasFinalSpend =
                        synchronizer.allTransactions.first().any {
                            it.isFinalClaimSpend(Zatoshi(payload.amountZatoshi.toLong()))
                        }
                    GiftClaimFinalization(
                        canSettle = hasFinalSpend && residual <= MAX_ABANDONED_RESIDUAL,
                        residual = residual,
                    )
                } finally {
                    withContext(NonCancellable) { synchronizer.closeAndAwait() }
                }
            finalization
        }
    }

    override suspend fun cleanupFinalizedClaim(
        payload: GiftLinkPayload,
        cardAddress: String,
        network: ZcashNetwork,
    ) {
        val alias = giftAlias(payload.network, cardAddress)
        lockFor(alias).withLock {
            check(deleteWallet(alias, network)) { "Gift claim wallet cleanup failed" }
        }
    }

    private suspend fun readHoldings(
        synchronizer: Synchronizer,
        payload: GiftLinkPayload,
        fundingTxid: String,
    ): GiftCardHoldings {
        val account = synchronizer.getAccounts().first()
        val balance =
            synchronizer.walletBalances
                .filterNotNull()
                .first()
                .getValue(account.accountUuid)
        val transactions = synchronizer.allTransactions.first()
        val amount = Zatoshi(payload.amountZatoshi.toLong())
        return GiftCardHoldings(
            available = balance.shieldedAvailable(),
            total = balance.shieldedTotal(),
            hasFundingArrived = transactions.any { it.minedHeight != null && it.txId.txIdString() == fundingTxid },
            hasFinalClaimSpend = transactions.any { it.isFinalClaimSpend(amount) },
            hasPendingClaimSpend = transactions.any { it.isPendingClaimSpend(amount) },
        )
    }

    private fun lockFor(alias: String): Mutex = claimLocks.getOrPut(alias) { Mutex() }

    /**
     * Deletes the card's wallet by file rather than through `Synchronizer.erase`.
     *
     * `erase` takes an alias, but only its database deletion honours it: it first calls
     * `StandardPreferenceProvider(context).clear()` and `EncryptedPreferenceProvider(context)
     * .clear()`, both SDK-wide. The main wallet's `PendingSubmitPlanStore` lives in that encrypted
     * file — namespaced inside the blob, not by file — so erasing a card would drop resubmission
     * metadata for unrelated transactions. Verified in the 3.0.1-SNAPSHOT AAR.
     *
     * [NonCancellable] because the decision to delete is already made by the time this runs, and a
     * cancelled caller would throw on the way in and strand a bearer seed on disk.
     */
    private suspend fun deleteWallet(alias: String, network: ZcashNetwork) =
        withContext(NonCancellable + Dispatchers.IO) {
            val root = File(context.noBackupFilesDir, SDK_NO_BACKUP_SUBDIRECTORY)
            giftWalletFileNames(alias, network.networkName)
                .map { name ->
                    val file = File(root, name)
                    val deleted = runCatching { !file.exists() || file.deleteRecursively() }.getOrDefault(false)
                    if (!deleted) Twig.warn { "Gift claim: $name could not be deleted" }
                    deleted
                }.all { it }
        }

    /**
     * Opens the card's wallet.
     *
     * `Synchronizer.new` fails closed with [InitializeException.SeedNotRelevant] rather than
     * silently opening the wrong wallet (confirmed on device, §7.1), and that is left to propagate:
     * recovering by erasing the alias would take the main wallet's preferences with it — see
     * [deleteWallet] — and the alias is a SHA-256 of the card's network and address, so a database
     * under it holding a different seed cannot arise from any real card.
     */
    private suspend fun open(
        payload: GiftLinkPayload,
        network: ZcashNetwork,
        endpoint: LightWalletEndpoint,
        alias: String,
    ): CloseableSynchronizer {
        val seed = giftKeyProvider.deriveSeed(payload.mnemonic)
        return try {
            Synchronizer.new(
                alias = alias,
                birthday = BlockHeight.new(payload.birthdayHeight),
                context = context,
                lightWalletEndpoint = endpoint,
                setup =
                    AccountCreateSetup(
                        accountName = GIFT_ACCOUNT_NAME,
                        keySource = null,
                        seed = FirstClassByteArray(seed),
                    ),
                walletInitMode = WalletInitMode.RestoreWallet,
                zcashNetwork = network,
                // A bearer card is already public to whoever holds the link, and Tor here would
                // add a second circuit alongside the wallet's own for no privacy the link has not
                // already given away.
                isTorEnabled = false,
                isExchangeRateEnabled = false,
            )
        } finally {
            seed.fill(0)
        }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private suspend fun claimFrom(
        synchronizer: CloseableSynchronizer,
        payload: GiftLinkPayload,
        recipientAddress: String,
        resumeEvidence: GiftClaimResumeEvidence,
        onBeforeSubmit: suspend () -> Unit,
        onProgress: (GiftClaimProgress) -> Unit,
    ): GiftClaimOutcome {
        // Same bound as `inspect`, and for the same reason: the scan that follows is deliberately
        // unbounded (§11.1), but a server that cannot be reached at all must say so rather than
        // leave the recipient on a bar that will never move.
        awaitReachable(synchronizer)
        awaitSynced(synchronizer, onProgress)

        val account = synchronizer.getAccounts().first()
        val balance =
            synchronizer.walletBalances
                .filterNotNull()
                .first()
                .getValue(account.accountUuid)
        val amount = Zatoshi(payload.amountZatoshi.toLong())

        // Resume a transaction retained by the isolated database instead of double-spending.
        val transactions = synchronizer.allTransactions.first()
        val outgoingClaims =
            transactions.filter { it.isFinalClaimSpend(amount) || it.isPendingClaimSpend(amount) }
        val finalOutgoingIds =
            outgoingClaims
                .filter { it.transactionState == TransactionState.Confirmed }
                .map { it.txId.txIdString() }
                .toSet()
        val pendingOutgoingIds = outgoingClaims.map { it.txId.txIdString() }.toSet() - finalOutgoingIds
        val locallySubmittedTxIds =
            when {
                resumeEvidence.claimTxIds.isNotEmpty() -> {
                    resumeEvidence.claimTxIds
                }

                resumeEvidence.submissionWasAttempted -> {
                    // A marker proves only that this process crossed the durable boundary. It does
                    // not prove that every later spend of this bearer card was ours: the process
                    // can die before transaction creation and another link holder can claim next.
                    // In marker-only recovery, the pinned destination is the ownership evidence.
                    outgoingClaims
                        .filter { transaction ->
                            synchronizer
                                .getRecipients(transaction)
                                .toList()
                                .any { it.addressValue == recipientAddress }
                        }.map {
                            it.txId.txIdString()
                        }.toSet()
                }

                else -> {
                    emptySet()
                }
            }
        when (
            classifyOutgoingGiftClaim(
                finalTxIds = finalOutgoingIds,
                pendingTxIds = pendingOutgoingIds,
                locallySubmittedTxIds = locallySubmittedTxIds,
            )
        ) {
            GiftOutgoingClaimDisposition.ALREADY_CLAIMED -> {
                return GiftClaimOutcome.AlreadyClaimed
            }

            GiftOutgoingClaimDisposition.AWAITING_FINALITY -> {
                return GiftClaimOutcome.AwaitingFunding
            }

            GiftOutgoingClaimDisposition.NONE,
            GiftOutgoingClaimDisposition.RESUME,
            -> {
                Unit
            }
        }
        val resumedClaims = outgoingClaims.filter { it.txId.txIdString() in locallySubmittedTxIds }

        val finalClaims = resumedClaims.filter { it.transactionState == TransactionState.Confirmed }
        val pendingClaims =
            resumedClaims.filter {
                it.isPendingClaimSpend(amount) ||
                    (
                        finalClaims.isNotEmpty() &&
                            it.isSentTransaction &&
                            it.transactionState == TransactionState.Pending
                    )
            }
        if (pendingClaims.isNotEmpty()) {
            return GiftClaimOutcome.Claimed(amount, pendingClaims.map { it.txId.txIdString() })
        }
        val available = balance.shieldedAvailable()
        if (finalClaims.isNotEmpty() && available <= MAX_ABANDONED_RESIDUAL) {
            return GiftClaimOutcome.Claimed(amount, finalClaims.map { it.txId.txIdString() })
        }

        if (finalClaims.isEmpty()) unspendable(synchronizer, balance, amount)?.let { return it }

        val requested =
            if (finalClaims.isEmpty()) amount else available - MAX_ABANDONED_RESIDUAL
        val initialProposal =
            try {
                synchronizer.proposeTransfer(account, recipientAddress, requested, "")
            } catch (e: TransactionEncoderException.ProposalFromParametersException) {
                // The card holds its amount but cannot also cover the fee to move it. Waiting does
                // not fix that — the funding is one note, so it is either confirmed in full or not
                // present at all — so this is reported as a short card rather than as a wait that
                // would re-check every 45 seconds forever. Left unsettled: the funds are untouched
                // and the database stays resumable.
                if (!e.isInsufficientFunds()) throw e
                Twig.warn { "Gift claim: card cannot cover its own claim fee" }
                return GiftClaimOutcome.Underfunded(available)
            }
        val sweepAmount = available - initialProposal.totalFeeRequired()
        val proposal =
            if (sweepAmount > requested) {
                runCatching { synchronizer.proposeTransfer(account, recipientAddress, sweepAmount, "") }
                    .getOrDefault(initialProposal)
            } else {
                initialProposal
            }
        val usk = giftKeyProvider.deriveSpendingKey(payload.mnemonic, synchronizer.network)

        // NonCancellable covers the verdict, not just the broadcast. The sync above is abandonable;
        // a broadcast is not — cancelling between submitting and returning leaves nobody knowing
        // whether the money moved, on a card with no reclaim. The refreshes inside are best-effort
        // for the same reason.
        onBeforeSubmit()
        return withContext(NonCancellable) {
            val result =
                synchronizer
                    .createProposedTransactions(proposal, usk)
                    .toList()
                    .toSubmitResult()

            if (result !is SubmitResult.Success) {
                Twig.warn { "Gift claim: broadcast was not a clean success" }
                GiftClaimOutcome.NotBroadcast(result)
            } else {
                if (synchronizer is SdkSynchronizer) {
                    runCatching {
                        synchronizer.refreshTransactions()
                        synchronizer.refreshAllBalances()
                    }.onFailure { Twig.warn { "Gift claim: post-claim refresh failed" } }
                }
                GiftClaimOutcome.Claimed(
                    amount = amount,
                    txIds = finalClaims.map { it.txId.txIdString() } + result.txIds,
                )
            }
        }
    }

    /**
     * Renders the wait as a bar that fills rather than a dead end the recipient keeps poking.
     *
     * Counted from the earliest mined transaction that actually *delivered* the card amount, not
     * from the earliest mined transaction of any kind. The card's address is plaintext in the link,
     * so anyone holding it can mine a transparent dust send into this history ahead of the funding
     * — the same reason [GiftCardHoldings.hasFundingArrived] refuses to read "any mined
     * transaction" as evidence. Null when there is nothing that qualifies, and the screen then
     * shows the wait without a count rather than a wrong one.
     */
    private suspend fun GiftClaimOutcome.NotYetSpendable.withConfirmations(
        synchronizer: Synchronizer,
        amount: Zatoshi,
    ): GiftClaimOutcome.NotYetSpendable {
        val mined =
            synchronizer
                .fundingCandidates(amount)
                .mapNotNull { it.minedHeight?.value }
                .minOrNull()
        val tip = synchronizer.networkHeight.value?.value
        val confirmations =
            if (tip == null || mined == null) null else (tip - mined + 1).coerceAtLeast(0L).toInt()
        return copy(confirmations = confirmations)
    }

    /**
     * Why the card cannot be spent right now, or null when it can.
     *
     * An empty wallet is the one answer that cannot resolve itself, and this is the claim path's
     * version of the split `inspect` makes with a funding txid it does not have here: a card the
     * money never reached is not a card somebody emptied. The recipient can be holding a link whose
     * funding is still in the mempool — the sender may share in the ~75 seconds before it mines —
     * and settling that would throw away a database the retry has to rebuild from the card's
     * birthday, minutes of scanning for a gift that was always going to arrive (§11.1).
     */
    private suspend fun unspendable(
        synchronizer: Synchronizer,
        balance: AccountBalance,
        amount: Zatoshi,
    ): GiftClaimOutcome? {
        val available = balance.shieldedAvailable()
        val total = balance.shieldedTotal()
        return when {
            available >= amount -> {
                null
            }

            total > Zatoshi.ZERO -> {
                GiftClaimOutcome
                    .NotYetSpendable(available, total, confirmations = null)
                    .withConfirmations(synchronizer, amount)
            }

            else -> {
                // Empty either way, but only a wallet that once held the card's amount is a wallet
                // somebody emptied. Nothing at all means the funding has not landed yet.
                GiftClaimOutcome.AwaitingFunding
            }
        }
    }

    /**
     * The mined incoming transactions large enough to be this card's funding.
     *
     * Incoming and at least the card amount, because the address is public: a stranger's dust is
     * neither this card's money nor evidence about it.
     */
    private suspend fun Synchronizer.fundingCandidates(amount: Zatoshi) =
        allTransactions.first().filter {
            it.minedHeight != null && !it.isSentTransaction && it.netValue >= amount
        }

    /**
     * Bounds only the part that can hang forever: reaching the server at all. The scan that follows
     * is deliberately unbounded — a legitimate one runs for minutes (§11.1) and the screen offers a
     * stop — but a check is optional, so an unreachable server must fail it rather than freeze it.
     */
    private suspend fun awaitReachable(synchronizer: Synchronizer) {
        withTimeoutOrNull(SERVER_TIMEOUT) {
            synchronizer.status.first {
                when (it) {
                    Synchronizer.Status.STOPPED -> throw GiftCardSynchronizerStoppedException()
                    Synchronizer.Status.SYNCING, Synchronizer.Status.SYNCED -> true
                    Synchronizer.Status.INITIALIZING, Synchronizer.Status.DISCONNECTED -> false
                }
            }
        } ?: throw GiftCardUnreachableException()
    }

    private suspend fun awaitSynced(
        synchronizer: Synchronizer,
        onProgress: (GiftClaimProgress) -> Unit,
    ) = coroutineScope {
        val fraction = MutableStateFlow(0f)
        val watchdog =
            launch {
                failWhenScanStalls(
                    scannedHeight = { synchronizer.fullyScannedHeight.value?.value },
                    fraction = { fraction.value },
                )
            }
        // Plain Flows, not StateFlows: no `.value` to poll, so the wait is the collection itself.
        combine(synchronizer.status, synchronizer.progress) { status, progress -> status to progress }
            .first { (status, progress) ->
                if (status == Synchronizer.Status.STOPPED) throw GiftCardSynchronizerStoppedException()
                fraction.value = progress.decimal
                onProgress(
                    GiftClaimProgress(
                        status = status,
                        fraction = progress.decimal,
                        scannedHeight = synchronizer.fullyScannedHeight.value?.value,
                        tipHeight = synchronizer.networkHeight.value?.value,
                    )
                )
                status == Synchronizer.Status.SYNCED
            }
        watchdog.cancel()
    }

    private companion object {
        /**
         * Generous on purpose: this waits out a *cold* isolated synchronizer creating its database
         * and connecting from scratch, so a short bound fails checks that would have worked.
         */
        val SERVER_TIMEOUT = 90.seconds
        val MAX_ABANDONED_RESIDUAL = Zatoshi(10_000L)

        const val GIFT_ACCOUNT_NAME = "gift"
        const val ALIAS_HASH_CHARS = 48

        /**
         * A stable per-card alias, so an interrupted claim resumes against the same database rather
         * than rescanning from the card's birthday.
         *
         * From the **address, not the mnemonic**: the address already identifies the card, and this
         * becomes a filesystem path component. `ZcashSdk` wants 1..99 characters of letters, digits,
         * hyphens and underscores; this is 53 and hex.
         */
        fun giftAlias(network: String, address: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest("$network:$address".toByteArray())
            return "gift_" + hash.joinToString("") { "%02x".format(it) }.take(ALIAS_HASH_CHARS)
        }
    }
}
