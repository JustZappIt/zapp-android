package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.model.Proposal
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PendingImmediateProposalRepositoryTest {
    @Test
    fun setThenGetReturnsTheStoredProposal() {
        val repository: PendingImmediateProposalRepository = PendingImmediateProposalRepositoryImpl()
        val proposal = mockk<Proposal>()

        repository.set(proposal)

        assertEquals(proposal, repository.get())
    }

    @Test
    fun getReturnsNullBeforeAnythingIsSet() {
        val repository: PendingImmediateProposalRepository = PendingImmediateProposalRepositoryImpl()

        assertNull(repository.get())
    }

    @Test
    fun clearRemovesTheStoredProposal() {
        val repository: PendingImmediateProposalRepository = PendingImmediateProposalRepositoryImpl()
        repository.set(mockk<Proposal>())

        repository.clear()

        assertNull(repository.get())
    }
}
