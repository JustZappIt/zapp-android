package co.electriccoin.zcash.ui.common.repository

import co.electriccoin.zcash.ui.common.provider.PeerCashOutCheckpointStorageProvider
import co.electriccoin.zcash.ui.common.provider.PeerPayeeHandleProvider
import co.electriccoin.zcash.ui.common.provider.PeerPayeeRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.peer.PayeeHandle
import xyz.justzappit.offramp.peer.PayeeHash
import xyz.justzappit.offramp.peer.PeerCashOutCheckpoint
import xyz.justzappit.offramp.peer.PeerCashOutId
import xyz.justzappit.offramp.peer.PeerCashOutOrchestrator
import xyz.justzappit.offramp.peer.PeerCashOutRequest
import xyz.justzappit.offramp.peer.PeerCashOutStatus
import xyz.justzappit.offramp.peer.PeerCashOutStep
import xyz.justzappit.offramp.peer.PeerCurrency
import xyz.justzappit.offramp.peer.PeerDepositId
import xyz.justzappit.offramp.peer.PeerErrorCode
import xyz.justzappit.offramp.peer.PeerOrderSnapshot
import xyz.justzappit.offramp.peer.PeerPlatform
import xyz.justzappit.offramp.peer.asError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An attempt owns its payee. The stored handle is per platform and is whatever the user last typed,
 * so reading it at resume time is how a retry of Alice's cash-out ends up paying Bob.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerCashOutRepositoryTest {
    private val orchestrator = RecordingOrchestrator()
    private val checkpoints = InMemoryCheckpointStorage()
    private val payees = InMemoryPayeeHandles()

    private fun repository() =
        PeerCashOutRepositoryImpl(
            orchestrator = orchestrator,
            checkpointStorage = checkpoints,
            payeeHandleProvider = payees,
            dispatcher = UnconfinedTestDispatcher(),
        )

    /** The reported bug: the retry paid whatever handle happened to be stored for the rail. */
    @Test
    fun `a retry keeps the handle its attempt was opened for`() =
        runTest {
            payees.store(PeerPlatform.REVOLUT, handle(ALICE), hash = null)
            val repository = repository()
            val id = repository.newId()
            repository.start(id, request(ALICE))

            payees.store(PeerPlatform.REVOLUT, handle(BOB), hash = null)
            repository.resume(id)

            assertEquals(listOf(ALICE, ALICE), orchestrator.requests.map { it.handle?.value })
        }

    /**
     * Recovery from a checkpoint alone: the hash it carries is enough, and the handle it deliberately
     * never stored must not be substituted from the rail's current record.
     */
    @Test
    fun `a resume from a checkpoint uses its registered hash and no handle`() =
        runTest {
            payees.store(PeerPlatform.REVOLUT, handle(BOB), hash = null)
            checkpoints.store(checkpoint())
            val repository = repository()

            repository.resume(CASH_OUT_ID)

            assertEquals(listOf(PayeeHash.parse(PAYEE_HEX)), orchestrator.resumed.map { it.payeeHash })
            assertTrue(orchestrator.requests.isEmpty())
        }

    @Test
    fun `an unknown attempt with no checkpoint resumes nothing`() =
        runTest {
            val repository = repository()

            repository.resume(CASH_OUT_ID)

            assertTrue(orchestrator.requests.isEmpty())
            assertTrue(orchestrator.resumed.isEmpty())
        }

    /**
     * The reported bug. The progress screen resumes on every entry, and once the order was live the
     * checkpoint was gone and the job finished — so a re-entry read "no checkpoint, no job, but a
     * request" as a fresh start and broadcast a second deposit.
     */
    @Test
    fun `an attempt that reached the chain never re-enters createOrder`() =
        runTest {
            orchestrator.createResult = PeerCashOutStatus.Withdrawn(DEPOSIT_ID, Usdc6.ofMicros(1_000_000L))
            val repository = repository()
            val id = repository.newId()
            repository.start(id, request(ALICE))

            repository.resume(id)

            assertEquals(1, orchestrator.requests.size)
            assertTrue(orchestrator.resumed.isEmpty())
        }

    /**
     * After process death the checkpoint is the only record of an attempt the indexer cannot answer
     * for. A surface built from runs plus the chain showed nothing, while the amount stayed
     * subtracted from Available with no route back to it.
     */
    @Test
    fun `a checkpoint from a previous process becomes a visible, dormant attempt`() =
        runTest {
            checkpoints.store(checkpoint())

            val repository = repository()

            val run = repository.runs.value.single()
            assertEquals(CASH_OUT_ID, run.id)
            assertEquals(Usdc6.ofMicros(1_000_000L), run.amount)
            assertFalse(run.isDriving)
            assertTrue(run.holdsFunds)
            assertTrue(orchestrator.requests.isEmpty())
            assertTrue(orchestrator.resumed.isEmpty())
        }

    /** An indexed checkpoint is already in the chain list; a second row for it is a double count. */
    @Test
    fun `a checkpoint whose deposit is known is left to the chain list`() =
        runTest {
            checkpoints.store(checkpoint().copy(depositId = DEPOSIT_ID))

            val repository = repository()

            assertTrue(repository.runs.value.isEmpty())
        }

    /**
     * The amount screen validates against a balance with committed attempts already subtracted, so
     * the reservation has to exist from the moment the attempt does. (Where in the dispatch it lands
     * is not observable here: every test dispatcher either runs the launch eagerly or defers the
     * `stateIn` that publishes [PeerCashOutRepository.runs] alongside it.)
     */
    @Test
    fun `a started attempt is registered as holding its amount`() {
        val repository = repository()
        val id = repository.newId()

        repository.start(id, request(ALICE))

        val run = repository.runs.value.single()
        assertEquals(Usdc6.ofMicros(1_000_000L), run.amount)
        assertTrue(run.holdsFunds)
    }

    /** Reconciliation is the only thing that can settle an attempt nothing is driving any more. */
    @Test
    fun `a reconciled deposit settles a dormant attempt`() =
        runTest {
            checkpoints.store(checkpoint())
            val repository = repository()

            repository.onDepositReconciled(CASH_OUT_ID, DEPOSIT_ID)

            val run = repository.runs.value.single()
            assertEquals(DEPOSIT_ID, run.depositId)
            assertFalse(run.holdsFunds)
        }

    @Test
    fun `a withdrawal that lands is kept and stamped, not forgotten`() =
        runTest {
            val repository = repository()

            repository.withdraw(DEPOSIT_ID, Usdc6.ofMicros(1_000_000L))

            // Dropping it here re-armed the withdraw button while the order poll still reported the
            // balance this withdrawal had already taken, and the second tap sent a second one.
            val action = repository.orderActions.value[DEPOSIT_ID]
            assertEquals(PeerOrderActionKind.WITHDRAW, action?.kind)
            assertEquals(false, action?.isRunning)
            assertNull(action?.failure)
            assertTrue(action?.awaitsConfirmation(readAtMillis = null) == true)
        }

    /**
     * A settled action is still in its own `finally` for a moment after it releases the claim.
     * Reading that as a live one drops the second withdrawal while the button reports it sent.
     */
    @Test
    fun `a withdrawal issued after the previous one settles still reaches the chain`() =
        runTest {
            val repository = repository()

            repository.withdraw(DEPOSIT_ID, Usdc6.ofMicros(1_000_000L))
            repository.clearOrderAction(DEPOSIT_ID)
            repository.withdraw(DEPOSIT_ID, Usdc6.ofMicros(2_000_000L))

            assertEquals(
                listOf(Usdc6.ofMicros(1_000_000L), Usdc6.ofMicros(2_000_000L)),
                orchestrator.withdrawals,
            )
        }

    @Test
    fun `a failed action keeps its reason for the screen to show`() =
        runTest {
            orchestrator.withdrawResult =
                PeerCashOutStatus.Failed(
                    step = PeerCashOutStep.WITHDRAWING,
                    error = PeerErrorCode.INSUFFICIENT_AVAILABLE_FUNDS.asError(),
                )
            val repository = repository()

            repository.withdraw(DEPOSIT_ID, Usdc6.ofMicros(1_000_000L))

            val action = repository.orderActions.value[DEPOSIT_ID]
            assertEquals(PeerOrderActionKind.WITHDRAW, action?.kind)
            assertEquals(false, action?.isRunning)
            assertEquals(PeerErrorCode.INSUFFICIENT_AVAILABLE_FUNDS, action?.failure?.error?.code)
        }

    @Test
    fun `a reset leaves nothing driving the previous wallet`() =
        runTest {
            payees.store(PeerPlatform.REVOLUT, handle(ALICE), hash = null)
            val repository = repository()
            repository.start(repository.newId(), request(ALICE))

            repository.reset()

            assertTrue(repository.runs.value.isEmpty())
            assertTrue(repository.orderActions.value.isEmpty())
        }

    private fun handle(raw: String) = PeerPlatform.REVOLUT.normalizeHandle(raw)

    private fun request(raw: String) =
        PeerCashOutRequest(
            platform = PeerPlatform.REVOLUT,
            handle = handle(raw),
            currencies = listOf(PeerCurrency.EUR),
            amount = Usdc6.ofMicros(1_000_000L),
        )

    private fun checkpoint() =
        PeerCashOutCheckpoint(
            id = CASH_OUT_ID,
            platform = PeerPlatform.REVOLUT,
            currencies = listOf(PeerCurrency.EUR),
            payeeHashHex = PAYEE_HEX,
            amountMicroDecimal = "1000000",
            blockBeforeCreateDeposit = "1000",
            createdAtMillis = 0L,
        )

    private class RecordingOrchestrator : PeerCashOutOrchestrator {
        val requests = mutableListOf<PeerCashOutRequest>()
        val resumed = mutableListOf<PeerCashOutCheckpoint>()
        val withdrawals = mutableListOf<Usdc6>()
        var withdrawResult: PeerCashOutStatus? = null
        var createResult: PeerCashOutStatus? = null

        override fun createOrder(request: PeerCashOutRequest): Flow<PeerCashOutStatus> {
            requests += request
            return flowOf(createResult ?: PeerCashOutStatus.Idle)
        }

        override fun resume(checkpoint: PeerCashOutCheckpoint): Flow<PeerCashOutStatus> {
            resumed += checkpoint
            return flowOf(PeerCashOutStatus.Idle)
        }

        override fun observeOrder(id: PeerDepositId): Flow<PeerCashOutStatus> = flowOf(PeerCashOutStatus.Idle)

        override fun withdraw(id: PeerDepositId, amount: Usdc6): Flow<PeerCashOutStatus> {
            withdrawals += amount
            return flowOf(withdrawResult ?: PeerCashOutStatus.Withdrawn(depositId = id, amount = amount))
        }

        override fun setAcceptingIntents(id: PeerDepositId, accepting: Boolean): Flow<PeerCashOutStatus> =
            flowOf(PeerCashOutStatus.Idle)

        override suspend fun activeOrders(): List<PeerOrderSnapshot> = emptyList()

        override suspend fun allOrders(): List<PeerOrderSnapshot> = emptyList()
    }

    private class InMemoryCheckpointStorage : PeerCashOutCheckpointStorageProvider {
        private val entries = MutableStateFlow<List<PeerCashOutCheckpoint>>(emptyList())

        override suspend fun get(id: PeerCashOutId) = entries.value.firstOrNull { it.id == id }

        override suspend fun all() = entries.value

        override suspend fun store(checkpoint: PeerCashOutCheckpoint) {
            entries.value = entries.value.filterNot { it.id == checkpoint.id } + checkpoint
        }

        override suspend fun clear(id: PeerCashOutId) {
            entries.value = entries.value.filterNot { it.id == id }
        }

        override fun observe(): Flow<List<PeerCashOutCheckpoint>> = entries
    }

    private class InMemoryPayeeHandles : PeerPayeeHandleProvider {
        private val entries = MutableStateFlow<Map<PeerPlatform, PeerPayeeRecord>>(emptyMap())

        override suspend fun get(platform: PeerPlatform) = entries.value[platform]

        override suspend fun store(platform: PeerPlatform, handle: PayeeHandle, hash: PayeeHash?) {
            entries.value = entries.value + (platform to PeerPayeeRecord(handle, hash))
        }

        override suspend fun clear(platform: PeerPlatform) {
            entries.value = entries.value - platform
        }

        override fun observe(platform: PeerPlatform) = entries.map { it[platform] }
    }

    private companion object {
        const val ALICE = "alice"
        const val BOB = "bob"
        val PAYEE_HEX = "0x" + "33".repeat(32)
        val CASH_OUT_ID: PeerCashOutId = PeerCashOutId.of(ByteArray(PeerCashOutId.SIZE_BYTES) { 5 })
        val DEPOSIT_ID: PeerDepositId =
            PeerDepositId(escrowHex = "0x0000000000000000000000000000000000000001", onchain = "7")
    }
}
