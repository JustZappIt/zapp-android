// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.TransactionOverview
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlock
import xyz.justzappit.offramp.atomicswap.AtomicSwapBlockedException
import xyz.justzappit.offramp.atomicswap.SwapZcash
import xyz.justzappit.offramp.atomicswap.ZcashTransaction
import xyz.justzappit.offramp.atomicswap.ZcashTransactionStatus
import xyz.justzappit.offramp.atomicswap.ZcashTxId
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The Zcash transactions both swap directions keep: sent again from their bytes, and followed until mined. */
class SwapZcashTransactions(
    private val synchronizers: SynchronizerProvider,
    private val wallet: PersistableWalletProvider,
    private val proposals: ProposalDataSource,
) : SwapZcash {
    override suspend fun chainHeight(): Long =
        withTimeoutOrNull(HEIGHT_TIMEOUT) {
            synchronizers
                .getSynchronizer()
                .networkHeight
                .filterNotNull()
                .first()
        }?.value ?: throw AtomicSwapBlockedException(AtomicSwapBlock.ZCASH_UNAVAILABLE, "the chain's height is unknown")

    /** Sends [transaction] as it was kept. A network that refuses it says so here; it stays kept all the same. */
    override suspend fun submit(transaction: ZcashTransaction) {
        val created =
            CreatedTransaction(
                FirstClassByteArray(
                    transaction.txId.hex
                        .hexToBytes()
                        .reversedArray()
                ),
                FirstClassByteArray(transaction.raw.hexToBytes()),
                BlockHeight.new(transaction.expiryHeight),
            )
        when (val result = proposals.submitTransaction(created, wallet.requirePersistableWallet().endpoint)) {
            is SubmitResult.Success -> {
                Unit
            }

            is SubmitResult.Failure -> {
                throw AtomicSwapBlockedException(
                    AtomicSwapBlock.ZCASH_REJECTED,
                    "the network refused ${transaction.txId}: ${result.code} ${result.description}",
                )
            }

            is SubmitResult.GrpcFailure, is SubmitResult.Partial, is SubmitResult.Error -> {
                throw AtomicSwapBlockedException(AtomicSwapBlock.ZCASH_UNAVAILABLE, "${transaction.txId} wasn't sent")
            }
        }
    }

    /** The wallet, synced to the chain's tip first. */
    suspend fun synced(): Synchronizer {
        val synchronizer = synchronizers.getSynchronizer()
        if (synchronizer.syncToTip(SYNC_TIMEOUT) != Synchronizer.SyncBurstResult.SYNCED_TO_TIP) {
            throw AtomicSwapBlockedException(AtomicSwapBlock.ZCASH_UNAVAILABLE, "the wallet can't sync now")
        }
        return synchronizer
    }

    /** [txId] as the history of [account], which sent it, shows it; null while the wallet holds no such transaction. */
    suspend fun find(
        account: AccountUuid,
        txId: ZcashTxId
    ): ZcashTransactionStatus? {
        val synchronizer = synchronizers.getSynchronizer()
        return synchronizer
            .getTransactions(account)
            .first()
            .firstOrNull { it.txId.txIdString() == txId.hex }
            ?.let { synchronizer.status(it) }
    }

    /** One the wallet lost, as a rescan can while it's unmined, expired once scanned past its expiry height. */
    suspend fun status(
        account: AccountUuid,
        transaction: ZcashTransaction
    ): ZcashTransactionStatus =
        find(account, transaction.txId)
            ?: if (synchronizers.getSynchronizer().hasScannedPast(transaction.expiryHeight)) {
                ZcashTransactionStatus.Expired
            } else {
                ZcashTransactionStatus.Unknown
            }

    private companion object {
        val HEIGHT_TIMEOUT = 30.seconds
        val SYNC_TIMEOUT = 2.minutes
    }
}

/** Expired only once every block to its expiry height is scanned: until then, one the tip passed may yet be mined. */
internal fun Synchronizer.status(overview: TransactionOverview): ZcashTransactionStatus {
    val mined = overview.minedHeight
    val expiry = overview.expiryHeight?.value?.takeIf { it > 0 }
    return when {
        mined != null -> {
            val tip = networkHeight.value?.value ?: mined.value
            ZcashTransactionStatus.Mined((tip - mined.value + 1).coerceAtLeast(1))
        }

        expiry != null && hasScannedPast(expiry) -> {
            ZcashTransactionStatus.Expired
        }

        else -> {
            ZcashTransactionStatus.Unmined
        }
    }
}

private fun Synchronizer.hasScannedPast(expiryHeight: Long): Boolean =
    fullyScannedHeight.value?.value?.let { it >= expiryHeight } == true

internal fun TransactionOverview.kept() =
    ZcashTransaction(
        ZcashTxId.parse(txId.txIdString()),
        checkNotNull(raw).byteArray.toHex(),
        checkNotNull(expiryHeight).value,
    )

internal fun CreatedTransaction.kept() =
    ZcashTransaction(ZcashTxId.parse(txIdString()), raw.byteArray.toHex(), checkNotNull(expiryHeight).value)
