package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.MessageAvailabilityDataSource
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.migration.MigrationAttentionKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

interface HomeMessageCacheRepository {
    /**
     * Last message that was shown. Null if no message has been shown yet.
     */
    var lastShownMessage: HomeMessageData?

    /**
     * Last message that was shown. Null if no message has been shown yet or if last message was null.
     */
    var lastMessage: HomeMessageData?

    fun init()

    fun reset()
}

class HomeMessageCacheRepositoryImpl(
    private val messageAvailabilityDataSource: MessageAvailabilityDataSource
) : HomeMessageCacheRepository {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override var lastShownMessage: HomeMessageData? = null
    override var lastMessage: HomeMessageData? = null

    override fun init() {
        messageAvailabilityDataSource
            .canShowMessage
            .onEach { canShowMessage ->
                if (canShowMessage) {
                    lastShownMessage = null
                    lastMessage = null
                }
            }.launchIn(scope)
    }

    override fun reset() {
        lastShownMessage = null
        lastMessage = null
    }
}

@Suppress("MagicNumber")
sealed interface HomeMessageData {
    val priority: Int

    data class Error(
        val synchronizerError: SynchronizerError
    ) : RuntimeMessage()

    data object Disconnected : RuntimeMessage()

    data class Restoring(
        val isSpendable: Boolean,
        val progress: Float
    ) : RuntimeMessage()

    data class Resyncing(
        val progress: Float
    ) : RuntimeMessage()

    data class Syncing(
        val progress: Float
    ) : RuntimeMessage()

    data object Updating : RuntimeMessage()

    data class ShieldFunds(
        val zatoshi: Zatoshi
    ) : RuntimeMessage()

    data class Migration(
        val plan: co.electriccoin.zcash.ui.common.model.migration.MigrationPlan?,
        val isComplete: Boolean = false,
        // Non-null exactly when the SDK's MigrationState is RequiresAttention (spec §6.2/§6.3) —
        // see MigrationAttentionKind's doc for why the two causes must never collapse into one
        // generic message again. attentionRangeText is only meaningful for TRANSFER_EXPIRED (the
        // specific "Transfer 3–5" range that actually expired); null for PLAN_UPDATE, whose home
        // message doesn't name a range (see design spec §6.2, no range mentioned there).
        val attentionKind: MigrationAttentionKind? = null,
        val attentionRangeText: String? = null,
    ) : RuntimeMessage()

    data object EnableTor : Prioritized {
        override val priority: Int = 3
    }

    data object Backup : Prioritized {
        override val priority: Int = 4
    }

    data object EnableCurrencyConversion : Prioritized {
        override val priority: Int = 2
    }

    data object CrashReport : Prioritized {
        override val priority: Int = 1
    }
}

/**
 * Message which always is shown.
 */
sealed class RuntimeMessage : HomeMessageData {
    override val priority: Int = Int.MAX_VALUE
}

/**
 * Message which always is displayed only if previous message was lower priority.
 */
sealed interface Prioritized : HomeMessageData
