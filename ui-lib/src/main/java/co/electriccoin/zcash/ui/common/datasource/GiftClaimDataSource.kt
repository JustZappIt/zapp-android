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
 * [fraction] comes from the SDK rather than being derived from heights here, and that is
 * load-bearing. **The scan does not start at the card's birthday.** `Synchronizer.new` snaps the
 * requested birthday down to a lower usable height — measured on testnet: a card born at 4,289,190
 * produced an account whose `birthday_height` was 4,235,171, some 54,000 blocks lower. Computing
 * `(scanned - birthday) / (tip - birthday)` therefore stays negative, clamps to zero for the whole
 * scan and then snaps to 100%, which is exactly the "stuck at 0%" the first build shipped.
 */
data class GiftClaimProgress(
    val status: Synchronizer.Status,
    val fraction: Float,
    val scannedHeight: Long?,
    val tipHeight: Long?,
)

/**
 * Confirmations a shielded note needs before it can be spent.
 *
 * `ConfirmationsPolicy::default()` is `untrusted: 10`, and a gift note arrives on the external
 * scope from a third party, so it takes that branch. No Android API exposes the policy, so this is
 * a mirror of the Rust default rather than something read from the SDK — see §4.
 */
const val REQUIRED_CONFIRMATIONS = 10

/** The SDK's own spelling of its no-backup subdirectory — `Files.NO_BACKUP_SUBDIRECTORY`, one `c`. */
private const val SDK_NO_BACKUP_SUBDIRECTORY = "co.electricoin.zcash"

/**
 * Everything `DatabaseCoordinator` creates for [alias], named relative to the SDK's no-backup
 * subdirectory. It is SDK-internal layout, so it lives in one tested function rather than inline.
 */
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
 * [Synchronizer.close] returns before shutdown finishes — it launches the teardown and documents
 * that it continues asynchronously — and the database files stay open until it does. Deleting them
 * in that window races the block-cache teardown and the WAL checkpoint.
 */
private suspend fun CloseableSynchronizer.closeAndAwait() {
    if (this is SdkSynchronizer) closeFlow().first() else close()
}

// Sums every shielded pool: a card is funded to a unified address and the sender's wallet picks
// the pool, so reading one would report a perfectly good card as empty the moment that changed.
private fun AccountBalance.shieldedAvailable() = sapling.available + orchard.available + ironwood.available

private fun AccountBalance.shieldedTotal() = sapling.total + orchard.total + ironwood.total

/** The card's server could not be reached at all, so nothing was learned about the card. */
class GiftCardUnreachableException : RuntimeException("Card wallet could not reach its server")

/** What a minted card's own wallet holds right now, and whether it ever held anything. */
data class GiftCardHoldings(
    val available: Zatoshi,
    val total: Zatoshi,
    /**
     * Whether the card's own wallet has a mined transaction in it.
     *
     * The card's wallet is created for one card and has no history before its funding, so a single
     * mined transaction anywhere in it is proof the money arrived — the funding itself, or the
     * claim that spent it. False means the funding has not mined: still in the mempool, or dropped
     * and possibly yet to mine before it expires.
     */
    val hasFundingArrived: Boolean,
) {
    /** Nothing left. On its own this does not say why — see [isCollected]. */
    val isEmpty: Boolean get() = total == Zatoshi.ZERO

    /**
     * Somebody took the money. The only reading of an empty wallet that settles a card, and it
     * needs both halves: an empty wallet that never held the funding was not collected, it was
     * never funded, and settling it would strand the card if its funding mined afterwards.
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
     * The money is there but not yet spendable — a shielded note needs ten confirmations, roughly
     * 12.5 minutes on mainnet. Telling the recipient the card is empty here would be a lie about a
     * perfectly good card, so this is deliberately its own case (§4.1).
     *
     * [confirmations] is how many the funding transaction has so far, when it can be worked out,
     * so the wait can be shown as progress rather than as a bare "try again".
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
 * The card's wallet is a second, entirely separate [Synchronizer] — never the app's own. The main
 * one is owned by `WalletCoordinator` and must not be extended or repointed: it holds the user's
 * real funds and a bearer seed has no business inside it. Running two concurrently was verified on
 * device before any of this was written (see `docs/gift-cards.md` §7.1): no SQLite contention,
 * per-alias database and block-cache paths, and a duplicate alias rejected outright.
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
     * Syncs the card's wallet and reports what it holds, spending nothing.
     *
     * This is the only way to learn whether a card was collected: the note is shielded, so nothing
     * short of scanning with the card's own viewing key can see it spent.
     */
    suspend fun inspect(
        payload: GiftLinkPayload,
        network: ZcashNetwork,
        endpoint: LightWalletEndpoint,
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
                // Always, on every path. An engine left running holds its database files
                // open and leaks a bearer seed into a background scan. NonCancellable because a
                // cancelled claim still has to shut its engine down, and this suspends.
                withContext(NonCancellable) { synchronizer.closeAndAwait() }
            }

        // Terminal outcomes only (§5). NotYetSpendable and NotBroadcast both resume against this
        // database, and rescanning from the card's birthday is the cost of throwing it away.
        if (outcome is GiftClaimOutcome.Claimed || outcome is GiftClaimOutcome.Empty) {
            deleteWallet(alias, network)
        }
        return outcome
    }

    override suspend fun inspect(
        payload: GiftLinkPayload,
        network: ZcashNetwork,
        endpoint: LightWalletEndpoint,
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
                    // A zero balance is ambiguous and the balance alone cannot resolve it, so the
                    // wallet's own history is read in the same breath: this is what separates
                    // "collected" from "the funding never landed". Same reasoning as
                    // `withConfirmations` — this wallet exists for one card and starts empty.
                    hasFundingArrived = synchronizer.allTransactions.first().any { it.minedHeight != null },
                )
            } finally {
                withContext(NonCancellable) { synchronizer.closeAndAwait() }
            }

        // Retained unless the card is settled, so the next check resumes instead of rescanning from
        // the card's birthday. Only a collected card is terminal — an empty wallet whose funding has
        // not arrived is one this will be asked about again, and rescanning it would be the whole
        // multi-minute sync over again.
        if (holdings.isCollected) deleteWallet(alias, network)
        return holdings
    }

    /**
     * Deletes the card's wallet by file rather than through `Synchronizer.erase`.
     *
     * `erase` takes an alias, but only its database deletion honours it: first it calls
     * `StandardPreferenceProvider(context).clear()` and `EncryptedPreferenceProvider(context)
     * .clear()`, both SDK-wide. The main wallet's `PendingSubmitPlanStore` lives in that same
     * encrypted file — namespaced inside the blob, not by file — so erasing a card would drop
     * resubmission metadata for unrelated transactions. Verified in the 3.0.1-SNAPSHOT AAR. The
     * alias is ours alone, so deleting its files reclaims the disk without touching anything shared.
     */
    private suspend fun deleteWallet(alias: String, network: ZcashNetwork) =
        withContext(Dispatchers.IO) {
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
     * silently opening the wrong wallet (confirmed on device, §7.1), and that is left to
     * propagate. Recovering by erasing the alias would take the main wallet's preferences with it
     * — see [claim] — and the alias is a SHA-256 of the card's network and address, so a database
     * under it holding a different seed is not a case that can arise from any real card.
     */
    private suspend fun open(
        payload: GiftLinkPayload,
        network: ZcashNetwork,
        endpoint: LightWalletEndpoint,
        alias: String,
    ): CloseableSynchronizer = create(payload, network, endpoint, alias, WalletInitMode.RestoreWallet)

    private suspend fun create(
        payload: GiftLinkPayload,
        network: ZcashNetwork,
        endpoint: LightWalletEndpoint,
        alias: String,
        initMode: WalletInitMode,
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
                walletInitMode = initMode,
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

        // NonCancellable from here down, and it covers the verdict rather than just the broadcast.
        // The sync above is abandonable — that is what stopping on app-lock cancels — but a
        // broadcast is not: cancelling between submitting and returning leaves nobody knowing
        // whether the money moved, on a card with no reclaim. Once this starts it runs to a
        // verdict, and the refreshes inside it are best-effort for the same reason: a failed
        // refresh must not turn a claim that succeeded into no answer at all.
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

    /**
     * Counts confirmations on the card's funding transaction, so a wait can be rendered as a bar
     * that fills rather than a dead end the recipient has to keep poking.
     */
    private suspend fun GiftClaimOutcome.withConfirmations(synchronizer: Synchronizer): GiftClaimOutcome {
        if (this !is GiftClaimOutcome.NotYetSpendable) return this
        // The earliest mined transaction in this wallet is the funding one: the card's wallet is
        // created for one card and has no history before it.
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

    /**
     * Why the card cannot be spent right now, or null when it can.
     *
     * Sums every shielded pool, because a card is funded to a unified address and the *sender's*
     * wallet picks the pool. In practice a fresh account receives Ironwood — ZIP 326 reuses the
     * Orchard receiver, so an ordinary scan finds it — but reading a single pool would report a
     * perfectly good card as empty the moment that changed.
     */
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
     * Bounds only the part that can hang forever: reaching the server at all.
     *
     * The scan that follows is deliberately unbounded — a legitimate one runs for minutes (§11.1)
     * and the screen offers a stop instead — but a check is optional, so an unreachable server has
     * to fail it rather than freeze it.
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
        // Both are plain Flows, not StateFlows — there is no `.value` to poll, so the wait is the
        // collection itself.
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
         * Generous on purpose. This waits out a *cold* isolated synchronizer, which creates its
         * database and connects from scratch, so a short bound fails checks that would have
         * worked. Only the unreachable case needs catching — a stop button covers the rest.
         */
        val SERVER_TIMEOUT = 90.seconds

        const val GIFT_ACCOUNT_NAME = "gift"
        const val ALIAS_HASH_CHARS = 48

        /**
         * A stable per-card alias, so an interrupted claim resumes against the same database
         * instead of rescanning from the card's birthday every time.
         *
         * Derived from the **address, not the mnemonic**: the address already identifies the card,
         * and this becomes a filesystem path component. `ZcashSdk` requires 1..99 characters of
         * letters, digits, hyphens and underscores; this is 53 and hex.
         */
        fun giftAlias(payload: GiftLinkPayload): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest("${payload.network}:${payload.address}".toByteArray())
            return "gift_" + hash.joinToString("") { "%02x".format(it) }.take(ALIAS_HASH_CHARS)
        }
    }
}
