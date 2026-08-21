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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import java.security.MessageDigest

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

/** What the card's own wallet turned out to hold. */
sealed interface GiftClaimOutcome {
    /** The funds are now in the recipient's wallet. Only this erases the isolated database. */
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

    /** The broadcast did not unambiguously succeed. The isolated database is retained. */
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
 * device before any of this was written (see `docs/GIFT_CARDS_PLAN.md` §7.1): no SQLite contention,
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
                // Always, on every path. An engine left running holds its database files open,
                // which both leaks a bearer seed into a background scan and makes erase fail.
                synchronizer.close()
            }

        // Erase on a clean success and on nothing else (§5). Every other outcome — a partial
        // broadcast, a transport failure that may still land, funds not yet confirmed — leaves
        // money reachable only through this database, and erasing it would strand that money for
        // good. A retained database costs disk; an erased one costs the card.
        if (outcome is GiftClaimOutcome.Claimed) {
            withContext(NonCancellable) {
                // After close, or the erase races the engine still holding the files (§7.1).
                delay(CLOSE_SETTLE_MILLIS)
                val erased = Synchronizer.erase(context, network, alias)
                Twig.info { "Gift claim: isolated wallet erased=$erased" }
            }
        }
        return outcome
    }

    /**
     * Opens the card's wallet, recreating it from scratch if the database on disk belongs to
     * someone else.
     *
     * A mismatched database is the cheap version of "verify it holds one account whose address
     * matches": `Synchronizer.new` fails closed with [InitializeException.SeedNotRelevant] rather
     * than silently opening the wrong wallet (confirmed on device, §7.1). Erasing here is safe
     * precisely *because* the seed does not match — no funds of this card can be in it.
     */
    private suspend fun open(
        payload: GiftLinkPayload,
        network: ZcashNetwork,
        endpoint: LightWalletEndpoint,
        alias: String,
    ): CloseableSynchronizer =
        try {
            create(payload, network, endpoint, alias, WalletInitMode.RestoreWallet)
        } catch (_: InitializeException.SeedNotRelevant) {
            Twig.warn { "Gift claim: isolated database for $alias belongs to another seed; recreating" }
            Synchronizer.erase(context, network, alias)
            create(payload, network, endpoint, alias, WalletInitMode.RestoreWallet)
        }

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

        // NonCancellable from here down. The sync above is abandonable — that is what stopping on
        // app-lock cancels — but a broadcast is not: cancelling between submitting and reading the
        // outcome leaves nobody knowing whether the money moved, on a card with no reclaim. Once
        // this starts it runs to a verdict.
        val result =
            withContext(NonCancellable) {
                synchronizer
                    .createProposedTransactions(proposal, usk)
                    .toList()
                    .toSubmitResult()
            }

        return if (result !is SubmitResult.Success) {
            // Retained on purpose. A partial or unreachable broadcast may still land, and the
            // database is the only key to whatever is left — erasing it strands the funds (§5).
            Twig.warn { "Gift claim: broadcast was not a clean success; retaining the isolated database" }
            GiftClaimOutcome.NotBroadcast(result)
        } else {
            if (synchronizer is SdkSynchronizer) {
                synchronizer.refreshTransactions()
                synchronizer.refreshAllBalances()
            }
            GiftClaimOutcome.Claimed(amount = amount, txIds = result.txIds)
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

    private fun AccountBalance.shieldedAvailable() =
        sapling.available + orchard.available + ironwood.available

    private fun AccountBalance.shieldedTotal() = sapling.total + orchard.total + ironwood.total

    private companion object {
        const val GIFT_ACCOUNT_NAME = "gift"
        const val ALIAS_HASH_CHARS = 48

        /** Long enough for the closed engine to release its database files before erase. */
        const val CLOSE_SETTLE_MILLIS = 1_000L

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
