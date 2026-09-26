// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.screen.advancedsettings.debug.atomicswap

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.exception.SdkException
import cash.z.ecc.android.sdk.ext.convertZatoshiToZecString
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountImportSetup
import cash.z.ecc.android.sdk.model.AccountPurpose
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedFullViewingKey
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.datasource.ATOMIC_SWAP_KEYSOURCE
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.provider.EncryptedJsonStore
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.usecase.GetWalletSeedBytesUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import xyz.justzappit.atomicswap.AtomicSwap
import xyz.justzappit.atomicswap.AtomicSwapException
import xyz.justzappit.atomicswap.SwapKey
import xyz.justzappit.evm.util.hexToBytes
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * M2's spike: takes a deposit in a swap's joint account back through the Zcash SDK, the way a
 * refunded swap will. The maker is a stand-in whose key is zecSwap's public test key, so its secret
 * is known; the ZEC stays safe because spending it also needs the user's half, from the seed.
 *
 * Steps: [prepare] shows the deposit address and records the height before funding, [import]
 * watches the account from that height, [sweep] proposes its balance home, signs the PCZT with the
 * combined key in libzecswap and sends it, and [delete] drops the account afterwards.
 */
class AtomicSwapRefundSpike(
    private val synchronizerProvider: SynchronizerProvider,
    private val accountDataSource: AccountDataSource,
    private val getWalletSeedBytes: GetWalletSeedBytesUseCase,
    encryptedPreferenceProvider: EncryptedPreferenceProvider,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val record = EncryptedJsonStore(encryptedPreferenceProvider, RECORD_KEY, SpikeRecord.serializer())
    private val mutableState = MutableStateFlow(AtomicSwapSpikeState())
    val state: StateFlow<AtomicSwapSpikeState> = mutableState.asStateFlow()
    private var job: Job? = null

    init {
        scope.launch { observeDeposit() }
    }

    fun prepare() =
        launch("prepare") {
            val synchronizer = testnet()
            val birthday =
                record.get()?.birthday
                    ?: withTimeout(HEIGHT_TIMEOUT) { synchronizer.networkHeight.filterNotNull().first() }.value
            record.set(SpikeRecord(birthday))
            val address = withKey(synchronizer) { AtomicSwap.depositAccount(it, MAKER_SHARE).address }
            mutableState.update { it.copy(depositAddress = address, birthday = birthday) }
            "deposit address ready; birthday $birthday"
        }

    fun import() =
        launch("import") {
            val synchronizer = testnet()
            val birthday = checkNotNull(record.get()?.birthday) { "prepare first" }
            depositAccount(synchronizer)?.let { return@launch "already imported" }
            val ufvk = withKey(synchronizer) { AtomicSwap.depositAccount(it, MAKER_SHARE).ufvk }
            val account =
                synchronizer.importAccountByUfvk(
                    AccountImportSetup(
                        accountName = "Swap deposit (spike)",
                        keySource = ATOMIC_SWAP_KEYSOURCE,
                        // An empty fingerprint imports a spending account with no derivation, as
                        // zecSwap's own wallet does: the SDK can then build spends it doesn't sign.
                        purpose = AccountPurpose.Spending(ByteArray(0), Zip32AccountIndex.new(0)),
                        ufvk = UnifiedFullViewingKey(ufvk),
                        birthday = BlockHeight.new(birthday),
                    )
                )
            "imported ${account.accountUuid}; it shows up once the wallet has scanned from $birthday"
        }

    fun sweep() =
        launch("sweep") {
            val synchronizer = testnet()
            val account = checkNotNull(depositAccount(synchronizer)) { "import first" }
            val balance = checkNotNull(synchronizer.walletBalances.value?.get(account.accountUuid)) { "no balance yet" }
            val spendable = balance.orchard.available + balance.ironwood.available
            val home =
                accountDataSource
                    .getZashiAccount()
                    .unified.address.address
            var fee = Zatoshi(ZIP317_TWO_ACTIONS)
            var proposal = synchronizer.proposeTransfer(account, home, spendable - fee)
            if (proposal.totalFeeRequired() != fee) {
                fee = proposal.totalFeeRequired()
                proposal = synchronizer.proposeTransfer(account, home, spendable - fee)
            }
            val pczt = synchronizer.createPcztFromProposal(account.accountUuid, proposal)
            val withProofs = synchronizer.addProofsToPczt(pczt.clonePczt())
            val redacted = synchronizer.redactPcztForSigner(pczt.clonePczt())
            val signed =
                withKey(synchronizer) {
                    AtomicSwap.signRefund(it, MAKER_SHARE, MAKER_SECRET, redacted.toByteArray())
                }
            val results = synchronizer.createTransactionFromPczt(withProofs, Pczt(signed)).toList()
            results.joinToString { result ->
                when (result) {
                    is TransactionSubmitResult.Success -> {
                        "sent ${(spendable - fee).taz()} (fee ${fee.taz()}): ${result.txIdString()}"
                    }

                    else -> {
                        "failed: $result"
                    }
                }
            }
        }

    fun delete() =
        launch("delete") {
            val synchronizer = testnet()
            val account = checkNotNull(depositAccount(synchronizer)) { "nothing imported" }
            check(synchronizer.deleteAccount(account.accountUuid)) { "the SDK kept the account" }
            record.clear()
            mutableState.update { AtomicSwapSpikeState(activity = it.activity) }
            "deleted the deposit account"
        }

    private suspend fun observeDeposit() {
        synchronizerProvider.walletBalances.collect { balances ->
            val synchronizer = synchronizerProvider.synchronizer.value ?: return@collect
            val account = depositAccount(synchronizer)
            val balance = account?.let { balances?.get(it.accountUuid) }
            mutableState.update {
                it.copy(
                    imported = account != null,
                    depositBalance =
                        balance?.let { b ->
                            "available ${(b.orchard.available + b.ironwood.available).taz()}, " +
                                "pending ${(b.orchard.valuePending + b.ironwood.valuePending).taz()}"
                        }
                )
            }
        }
    }

    private suspend fun depositAccount(synchronizer: Synchronizer): Account? =
        synchronizer.getAccounts().firstOrNull { it.keySource == ATOMIC_SWAP_KEYSOURCE }

    private suspend fun testnet(): Synchronizer {
        val synchronizer = synchronizerProvider.getSynchronizer()
        check(synchronizer.network == ZcashNetwork.Testnet) { "the spike runs on testnet only" }
        return synchronizer
    }

    private suspend fun <T> withKey(
        synchronizer: Synchronizer,
        block: (SwapKey) -> T
    ): T {
        val seed = getWalletSeedBytes()
        try {
            return block(SwapKey(seed, synchronizer.network == ZcashNetwork.Mainnet, SPIKE_INDEX))
        } finally {
            seed.fill(0)
        }
    }

    private fun launch(
        step: String,
        block: suspend () -> String
    ) {
        synchronized(this) {
            if (job?.isActive == true) return
            job =
                scope.launch {
                    mutableState.update { it.copy(busy = step, error = null) }
                    val line =
                        try {
                            "$step: ${block()}"
                        } catch (e: IllegalStateException) {
                            fail(step, e)
                        } catch (e: AtomicSwapException) {
                            fail(step, e)
                        } catch (e: IllegalArgumentException) {
                            fail(step, e)
                        } catch (e: SdkException) {
                            fail(step, e)
                        } catch (e: TimeoutCancellationException) {
                            fail(step, e)
                        }
                    mutableState.update { it.copy(busy = null, activity = it.activity + line) }
                }
        }
    }

    private fun fail(
        step: String,
        e: Exception
    ): String {
        Twig.warn(e) { "Atomic swap spike: $step failed" }
        mutableState.update { it.copy(error = "$step: ${e.message ?: e::class.simpleName}") }
        return "$step failed"
    }

    @Serializable
    private data class SpikeRecord(
        val birthday: Long
    )

    private companion object {
        const val RECORD_KEY = "atomicswap_refund_spike_v1"

        // Far from the indices real swaps count up from, so the spike never spends one.
        const val SPIKE_INDEX = 1_000_000

        // ZIP 317: 5,000 zatoshi per logical action, two at least; one note home is two.
        const val ZIP317_TWO_ACTIONS = 10_000L
        val HEIGHT_TIMEOUT = 30.seconds

        // zecSwap's public test maker: derive_maker_share(root = 32 bytes of 0x09, nonce 0).
        val MAKER_SHARE =
            (
                "187d300ebb59a5c9e7c9e61debd0b535a8a5cbbad4a5c46ac2a0597a06c13ee4" +
                    "329a369cf900745cdca87863f3f00de64d16e088bb1f5da341a2977547f9080a"
            ).hexToBytes()
        val MAKER_SECRET = "153423dedfb7aaff85c347e7dbb4c945feb1aad07cfece5693d2d1cd82915b54".hexToBytes()
    }
}

private fun Zatoshi.taz() = "${convertZatoshiToZecString(Locale.US)} TAZ"

data class AtomicSwapSpikeState(
    val depositAddress: String? = null,
    val birthday: Long? = null,
    val imported: Boolean = false,
    val depositBalance: String? = null,
    val busy: String? = null,
    val activity: List<String> = emptyList(),
    val error: String? = null,
)
