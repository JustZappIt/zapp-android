// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.datasource

import android.content.Context
import cash.z.ecc.android.sdk.CloseableSynchronizer
import cash.z.ecc.android.sdk.SdkSynchronizer
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.exception.InitializeException
import cash.z.ecc.android.sdk.model.AccountBalance
import cash.z.ecc.android.sdk.model.AccountCreateSetup
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.FirstClassByteArray
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
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

/**
 * Mined, not merely present: a transaction the wallet knows of but has not seen in a block is the
 * mempool case, and that is the one thing an empty card must never be settled on.
 */
private suspend fun Synchronizer.hasMined(txid: String): Boolean =
    allTransactions.first().any { it.minedHeight != null && it.txId.txIdString() == txid }

// Every shielded pool: the card is funded to a unified address and the *sender's* wallet picks
// which, so reading one pool would report a good card as empty the moment that changed.
private fun AccountBalance.shieldedAvailable() = sapling.available + orchard.available + ironwood.available

private fun AccountBalance.shieldedTotal() = sapling.total + orchard.total + ironwood.total

/** The card's server could not be reached at all, so nothing was learned about the card. */
class GiftCardUnreachableException : RuntimeException("Card wallet could not reach its server")

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
) {
    /** Nothing left. On its own this does not say why — see [isCollected]. */
    val isEmpty: Boolean get() = total == Zatoshi.ZERO

    /**
     * Somebody took the money — the only reading of an empty wallet that settles a card. Both halves
     * are needed: one that never held the funding was never funded, and settling it would strand
     * the card if the funding mined afterwards.
     */
    val isCollected: Boolean get() = hasFundingArrived && isEmpty
}

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

    /** Nothing ever arrived, or it has already been claimed by whoever else held the link. */
    data object Empty : GiftClaimOutcome

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
     * Syncs the card's wallet and, if its funds are spendable, moves exactly the card amount to
     * [recipientAddress].
     *
     * Never claims more than [GiftLinkPayload.amountZatoshi] even when more has arrived — see
     * `ClaimGiftCardUseCase` for why sweeping is the wrong shape.
     */
    suspend fun claim(
        payload: GiftLinkPayload,
        network: ZcashNetwork,
        endpoint: LightWalletEndpoint,
        recipientAddress: String,
        onProgress: (GiftClaimProgress) -> Unit,
    ): GiftClaimOutcome

    /**
     * Syncs the card's wallet and reports what it holds, spending nothing. The only way to learn
     * whether a card was collected: the note is shielded, so nothing short of scanning with the
     * card's own viewing key can see it spent.
     *
     * [fundingTxid] is required, not optional — see [GiftCardHoldings.hasFundingArrived]. A caller
     * without one has a card that was never funded.
     */
    suspend fun inspect(
        payload: GiftLinkPayload,
        network: ZcashNetwork,
        endpoint: LightWalletEndpoint,
        fundingTxid: String,
        onProgress: (GiftClaimProgress) -> Unit,
    ): GiftCardHoldings
}

internal class GiftClaimDataSourceImpl(
    private val context: Context,
    private val giftKeyProvider: GiftKeyProvider,
) : GiftClaimDataSource {
    override suspend fun claim(
        payload: GiftLinkPayload,
        network: ZcashNetwork,
        endpoint: LightWalletEndpoint,
        recipientAddress: String,
        onProgress: (GiftClaimProgress) -> Unit,
    ): GiftClaimOutcome {
        val alias = giftAlias(payload)
        val synchronizer = open(payload, network, endpoint, alias)
        val outcome =
            try {
                claimFrom(synchronizer, payload, recipientAddress, onProgress)
            } finally {
                // Every path: an engine left running holds its database files open and leaks a
                // bearer seed into a background scan. NonCancellable because a cancelled claim
                // still has to shut its engine down, and this suspends.
                withContext(NonCancellable) { synchronizer.closeAndAwait() }
            }

        // Terminal outcomes only (§5). NotYetSpendable and NotBroadcast resume against this
        // database, and rescanning from the card's birthday is the cost of throwing it away.
        //
        // The delete is NonCancellable itself, which is the easy half to miss: the broadcast runs
        // to a verdict regardless, but hands it back into a context that may have been cancelled
        // meanwhile, so every later suspension point throws. A claim that moved real money would
        // return CancellationException instead of its outcome, leaving a bearer seed on disk.
        if (outcome is GiftClaimOutcome.Claimed || outcome is GiftClaimOutcome.Empty) {
            deleteWallet(alias, network)
        }
        return outcome
    }

    override suspend fun inspect(
        payload: GiftLinkPayload,
        network: ZcashNetwork,
        endpoint: LightWalletEndpoint,
        fundingTxid: String,
        onProgress: (GiftClaimProgress) -> Unit,
    ): GiftCardHoldings {
        val alias = giftAlias(payload)
        val synchronizer = open(payload, network, endpoint, alias)
        val holdings =
            try {
                awaitReachable(synchronizer)
                awaitSynced(synchronizer, onProgress)
                val account = synchronizer.getAccounts().first()
                val balance =
                    synchronizer.walletBalances
                        .filterNotNull()
                        .first()
                        .getValue(account.accountUuid)
                GiftCardHoldings(
                    available = balance.shieldedAvailable(),
                    total = balance.shieldedTotal(),
                    // A zero balance cannot resolve itself, so the wallet's own history is read in
                    // the same breath: that is what separates "collected" from "never landed". The
                    // txid only says which transaction in that history counts.
                    hasFundingArrived = synchronizer.hasMined(fundingTxid),
                )
            } finally {
                withContext(NonCancellable) { synchronizer.closeAndAwait() }
            }

        // Retained unless settled, so the next check resumes instead of rescanning from the card's
        // birthday. An empty wallet whose funding has not arrived will be asked about again.
        if (holdings.isCollected) deleteWallet(alias, network)
        return holdings
    }

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
            giftWalletFileNames(alias, network.networkName).forEach { name ->
                val file = File(root, name)
                // deleteRecursively reports failure by returning false, not by throwing, so a
                // runCatching around it would never see the ordinary case.
                val deleted = runCatching { !file.exists() || file.deleteRecursively() }.getOrDefault(false)
                if (!deleted) Twig.warn { "Gift claim: $name could not be deleted" }
            }
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

    private suspend fun claimFrom(
        synchronizer: CloseableSynchronizer,
        payload: GiftLinkPayload,
        recipientAddress: String,
        onProgress: (GiftClaimProgress) -> Unit,
    ): GiftClaimOutcome {
        awaitSynced(synchronizer, onProgress)

        val account = synchronizer.getAccounts().first()
        val balance =
            synchronizer.walletBalances
                .filterNotNull()
                .first()
                .getValue(account.accountUuid)
        val amount = Zatoshi(payload.amountZatoshi.toLong())

        unspendable(balance, amount)?.let { return it.withConfirmations(synchronizer) }

        val proposal = synchronizer.proposeTransfer(account, recipientAddress, amount, "")
        val usk = giftKeyProvider.deriveSpendingKey(payload.mnemonic, synchronizer.network)

        // NonCancellable covers the verdict, not just the broadcast. The sync above is abandonable;
        // a broadcast is not — cancelling between submitting and returning leaves nobody knowing
        // whether the money moved, on a card with no reclaim. The refreshes inside are best-effort
        // for the same reason.
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
                GiftClaimOutcome.Claimed(amount = amount, txIds = result.txIds)
            }
        }
    }

    /** Renders the wait as a bar that fills rather than a dead end the recipient keeps poking. */
    private suspend fun GiftClaimOutcome.withConfirmations(synchronizer: Synchronizer): GiftClaimOutcome {
        if (this !is GiftClaimOutcome.NotYetSpendable) return this
        // The earliest mined transaction here is the funding one: this wallet has no prior history.
        val mined =
            synchronizer.allTransactions
                .first()
                .mapNotNull { it.minedHeight?.value }
                .minOrNull()
        val tip = synchronizer.networkHeight.value?.value
        val confirmations =
            if (tip == null || mined == null) null else (tip - mined + 1).coerceAtLeast(0L).toInt()
        return copy(confirmations = confirmations)
    }

    /** Why the card cannot be spent right now, or null when it can. */
    private fun unspendable(balance: AccountBalance, amount: Zatoshi): GiftClaimOutcome? {
        val available = balance.shieldedAvailable()
        val total = balance.shieldedTotal()
        return when {
            available >= amount -> null
            total > Zatoshi.ZERO -> GiftClaimOutcome.NotYetSpendable(available, total, confirmations = null)
            else -> GiftClaimOutcome.Empty
        }
    }

    /**
     * Bounds only the part that can hang forever: reaching the server at all. The scan that follows
     * is deliberately unbounded — a legitimate one runs for minutes (§11.1) and the screen offers a
     * stop — but a check is optional, so an unreachable server must fail it rather than freeze it.
     */
    private suspend fun awaitReachable(synchronizer: Synchronizer) {
        withTimeoutOrNull(SERVER_TIMEOUT) {
            synchronizer.status.first {
                it != Synchronizer.Status.INITIALIZING && it != Synchronizer.Status.DISCONNECTED
            }
        } ?: throw GiftCardUnreachableException()
    }

    private suspend fun awaitSynced(
        synchronizer: Synchronizer,
        onProgress: (GiftClaimProgress) -> Unit,
    ) {
        // Plain Flows, not StateFlows: no `.value` to poll, so the wait is the collection itself.
        combine(synchronizer.status, synchronizer.progress) { status, progress -> status to progress }
            .first { (status, progress) ->
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
    }

    private companion object {
        /**
         * Generous on purpose: this waits out a *cold* isolated synchronizer creating its database
         * and connecting from scratch, so a short bound fails checks that would have worked.
         */
        val SERVER_TIMEOUT = 90.seconds

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
        fun giftAlias(payload: GiftLinkPayload): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest("${payload.network}:${payload.address}".toByteArray())
            return "gift_" + hash.joinToString("") { "%02x".format(it) }.take(ALIAS_HASH_CHARS)
        }
    }
}
