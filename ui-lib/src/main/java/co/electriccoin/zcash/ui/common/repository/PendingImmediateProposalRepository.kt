package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.model.Proposal
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Transient, in-memory handoff of a not-yet-broadcast send-max [Proposal] between whichever
 * screen proposed an IMMEDIATE-mode migration (or a future "sweep the residual" flow — see
 * MigrationCompleteVM) and the Sending screen that actually signs and submits it. Mirrors
 * [PendingMigrationScheduleRepository]'s pattern exactly, for the same reason: the value needs to
 * survive a navigation hop without round-tripping through nav args. Not persisted: if the process
 * dies mid-flow, the user re-enters from the proposing screen and a fresh Proposal is built.
 */
interface PendingImmediateProposalRepository {
    fun set(proposal: Proposal)

    fun get(): Proposal?

    fun clear()
}

class PendingImmediateProposalRepositoryImpl : PendingImmediateProposalRepository {
    private val pending = MutableStateFlow<Proposal?>(null)

    override fun set(proposal: Proposal) {
        pending.value = proposal
    }

    override fun get(): Proposal? = pending.value

    override fun clear() {
        pending.value = null
    }
}
