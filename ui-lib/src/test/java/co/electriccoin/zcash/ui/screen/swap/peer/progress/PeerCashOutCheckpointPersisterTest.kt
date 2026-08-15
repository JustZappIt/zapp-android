package co.electriccoin.zcash.ui.screen.swap.peer.progress

import co.electriccoin.zcash.ui.common.provider.PeerCashOutCheckpointStorageProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.peer.PayeeHash
import xyz.justzappit.offramp.peer.PeerCashOutCheckpoint
import xyz.justzappit.offramp.peer.PeerCashOutId
import xyz.justzappit.offramp.peer.PeerCashOutRequest
import xyz.justzappit.offramp.peer.PeerCashOutStatus
import xyz.justzappit.offramp.peer.PeerCashOutStep
import xyz.justzappit.offramp.peer.PeerCurrency
import xyz.justzappit.offramp.peer.PeerDepositId
import xyz.justzappit.offramp.peer.PeerErrorCode
import xyz.justzappit.offramp.peer.PeerPlatform
import xyz.justzappit.offramp.peer.asError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The fund-safety guard. Two cash-outs can be unfinished at once, and a persister must never write
 * one attempt's amount over another's transaction hashes: on the next resume that resolves the wrong
 * deposit, or escrows a second lot of USDC.
 */
class PeerCashOutCheckpointPersisterTest {
    private val idA = PeerCashOutId.of(ByteArray(PeerCashOutId.SIZE_BYTES) { 0xA })
    private val idB = PeerCashOutId.of(ByteArray(PeerCashOutId.SIZE_BYTES) { 0xB })
    private val payeeHash = PayeeHash.parse("0x" + "11".repeat(BYTES32))
    private val txHashA = TxHash.fromHex("0x" + "ab".repeat(BYTES32))

    @Test
    fun `a second attempt never inherits the first attempt's submission`() =
        runTest {
            val storage = FakeStorage()
            drive(storage, idA, Usdc6.ofMicros(ONE_USDC)) {
                onStatus(PeerCashOutStatus.ValidatingPayee(PeerPlatform.REVOLUT, payeeHash))
                onStatus(creatingDeposit(Usdc6.ofMicros(ONE_USDC), txHashA))
            }
            val storedA = assertNotNull(storage.get(idA))

            drive(storage, idB, Usdc6.ofMicros(TWO_USDC)) {
                onStatus(PeerCashOutStatus.ValidatingPayee(PeerPlatform.REVOLUT, payeeHash))
                onStatus(creatingDeposit(Usdc6.ofMicros(TWO_USDC), txHash = null))
            }

            assertEquals(storedA, storage.get(idA))
            val storedB = assertNotNull(storage.get(idB))
            assertNull(storedB.createDepositTxHash)
            assertEquals(Usdc6.ofMicros(TWO_USDC), storedB.amount)
            assertEquals(2, storage.all().size)
        }

    @Test
    fun `seeding ignores a checkpoint belonging to another attempt`() =
        runTest {
            val storage = FakeStorage()
            drive(storage, idA, Usdc6.ofMicros(ONE_USDC)) {
                onStatus(PeerCashOutStatus.ValidatingPayee(PeerPlatform.REVOLUT, payeeHash))
                onStatus(creatingDeposit(Usdc6.ofMicros(ONE_USDC), txHashA))
            }
            val foreign = assertNotNull(storage.get(idA))

            val persister = persister(storage, idB, Usdc6.ofMicros(TWO_USDC))
            persister.seedFrom(foreign)
            persister.onStatus(PeerCashOutStatus.ValidatingPayee(PeerPlatform.REVOLUT, payeeHash))
            persister.onStatus(creatingDeposit(Usdc6.ofMicros(TWO_USDC), txHash = null))

            assertNull(assertNotNull(storage.get(idB)).createDepositTxHash)
        }

    @Test
    fun `only the settled attempt's record is cleared`() =
        runTest {
            val storage = FakeStorage()
            drive(storage, idA, Usdc6.ofMicros(ONE_USDC)) {
                onStatus(PeerCashOutStatus.ValidatingPayee(PeerPlatform.REVOLUT, payeeHash))
                onStatus(creatingDeposit(Usdc6.ofMicros(ONE_USDC), txHashA))
            }
            drive(storage, idB, Usdc6.ofMicros(TWO_USDC)) {
                onStatus(PeerCashOutStatus.ValidatingPayee(PeerPlatform.REVOLUT, payeeHash))
                onStatus(creatingDeposit(Usdc6.ofMicros(TWO_USDC), txHash = null))
                onStatus(PeerCashOutStatus.Withdrawn(depositId = DEPOSIT, amount = Usdc6.ofMicros(TWO_USDC)))
            }

            assertNotNull(storage.get(idA))
            assertNull(storage.get(idB))
        }

    /**
     * The reported bug: a `createDeposit` that provably reverted escrowed nothing, but its record
     * survived — so the amount stayed subtracted from Available, and every retry resolved the same
     * reverted receipt into the same failure.
     */
    @Test
    fun `a conclusively reverted deposit retires its record`() =
        runTest {
            val storage = FakeStorage()
            drive(storage, idA, Usdc6.ofMicros(ONE_USDC)) {
                onStatus(PeerCashOutStatus.ValidatingPayee(PeerPlatform.REVOLUT, payeeHash))
                onStatus(creatingDeposit(Usdc6.ofMicros(ONE_USDC), txHashA))
                onStatus(failed(PeerCashOutStep.CREATING_DEPOSIT, PeerErrorCode.TRANSACTION_FAILED))
            }

            assertNull(storage.get(idA))
        }

    /** The deposit may exist. Retiring the record here is what would escrow a second lot of USDC. */
    @Test
    fun `a submission whose outcome is unknown keeps its record`() =
        runTest {
            val storage = FakeStorage()
            drive(storage, idA, Usdc6.ofMicros(ONE_USDC)) {
                onStatus(PeerCashOutStatus.ValidatingPayee(PeerPlatform.REVOLUT, payeeHash))
                onStatus(creatingDeposit(Usdc6.ofMicros(ONE_USDC), txHashA))
                onStatus(failed(PeerCashOutStep.CREATING_DEPOSIT, PeerErrorCode.TRANSACTION_STATUS_UNKNOWN))
            }

            assertEquals(txHashA, assertNotNull(storage.get(idA)).createDepositTxHash)
        }

    /** Before the send the bridge handle is the only record of ZEC in flight; it outlives a failure. */
    @Test
    fun `a funding failure keeps the bridge handle it was the only record of`() =
        runTest {
            val storage = FakeStorage()
            drive(storage, idA, Usdc6.ofMicros(ONE_USDC)) {
                onStatus(PeerCashOutStatus.ValidatingPayee(PeerPlatform.REVOLUT, payeeHash))
                onStatus(
                    PeerCashOutStatus.BridgingFunds(
                        amount = Usdc6.ofMicros(ONE_USDC),
                        depositAddress = BRIDGE_HANDLE,
                    ),
                )
                onStatus(failed(PeerCashOutStep.FUNDING, PeerErrorCode.FUNDING_BRIDGE_FAILED))
            }

            assertEquals(BRIDGE_HANDLE, assertNotNull(storage.get(idA)).bridgeDepositAddress)
        }

    /** A resume must not lose the deposit id to an early status that simply does not carry one. */
    @Test
    fun `a known deposit survives statuses that carry none`() =
        runTest {
            val storage = FakeStorage()
            val seeded =
                PeerCashOutCheckpoint(
                    id = idA,
                    platform = PeerPlatform.REVOLUT,
                    currencies = listOf(PeerCurrency.EUR),
                    payeeHashHex = payeeHash.hex,
                    amountMicroDecimal = ONE_USDC.toString(),
                    createDepositTxHash = txHashA,
                    blockBeforeCreateDeposit = "1000",
                    depositId = DEPOSIT,
                    createdAtMillis = 1L,
                )
            storage.store(seeded)

            val persister = persister(storage, idA, Usdc6.ofMicros(ONE_USDC))
            persister.seedFrom(seeded)
            persister.onStatus(PeerCashOutStatus.Idle)

            assertEquals(DEPOSIT, assertNotNull(storage.get(idA)).depositId)
            assertEquals(1L, assertNotNull(storage.get(idA)).createdAtMillis)
        }

    private suspend fun drive(
        storage: FakeStorage,
        id: PeerCashOutId,
        amount: Usdc6,
        body: suspend PeerCashOutCheckpointPersister.() -> Unit,
    ) = persister(storage, id, amount).apply { body() }

    private fun persister(storage: FakeStorage, id: PeerCashOutId, amount: Usdc6) =
        PeerCashOutCheckpointPersister(
            storage = storage,
            id = id,
            request =
                PeerCashOutRequest(
                    platform = PeerPlatform.REVOLUT,
                    handle = PeerPlatform.REVOLUT.normalizeHandle("andrew1abc"),
                    currencies = listOf(PeerCurrency.EUR),
                    amount = amount,
                ),
            nowMillis = { NOW },
        )

    private fun creatingDeposit(amount: Usdc6, txHash: TxHash?) =
        PeerCashOutStatus.CreatingDeposit(amount = amount, fromBlockNumber = "1000", txHash = txHash)

    private fun failed(step: PeerCashOutStep, code: PeerErrorCode) =
        PeerCashOutStatus.Failed(step = step, error = code.asError())

    private class FakeStorage : PeerCashOutCheckpointStorageProvider {
        private val entries = MutableStateFlow<List<PeerCashOutCheckpoint>>(emptyList())

        override suspend fun get(id: PeerCashOutId) = entries.value.firstOrNull { it.id == id }

        override suspend fun all() = entries.value

        override suspend fun store(checkpoint: PeerCashOutCheckpoint) {
            entries.value = entries.value.filterNot { it.id == checkpoint.id } + checkpoint
        }

        override suspend fun clear(id: PeerCashOutId) {
            entries.value = entries.value.filterNot { it.id == id }
        }

        override fun observe(): Flow<List<PeerCashOutCheckpoint>> = entries.map { it }
    }

    private companion object {
        const val BYTES32 = 32
        const val ONE_USDC = 1_000_000L
        const val TWO_USDC = 2_000_000L
        const val NOW = 42L
        const val BRIDGE_HANDLE = "near-1click-deposit-address"
        val DEPOSIT =
            PeerDepositId(escrowHex = "0x777777779d229cdF3110e9de47943791c26300Ef", onchain = "3788")
    }
}
