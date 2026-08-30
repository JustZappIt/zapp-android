// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.apple

import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.types.TxHash
import xyz.justzappit.offramp.p2p.Usdc6
import xyz.justzappit.offramp.peer.PayeeHash
import xyz.justzappit.offramp.peer.PeerCashOutId
import xyz.justzappit.offramp.peer.PeerCashOutRequest
import xyz.justzappit.offramp.peer.PeerCashOutStatus
import xyz.justzappit.offramp.peer.PeerCashOutStep
import xyz.justzappit.offramp.peer.PeerCurrency
import xyz.justzappit.offramp.peer.PeerErrorCode
import xyz.justzappit.offramp.peer.PeerPlatform
import xyz.justzappit.offramp.peer.PeerRecovery
import xyz.justzappit.offramp.peer.PeerResumeAction
import xyz.justzappit.offramp.peer.asError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a resume is allowed to do after each point in the flow. Every case here is one the app can be
 * killed at, and the wrong answer at any of them either broadcasts a second `createDeposit` — two
 * escrows for one order — or drops the only record of the first.
 */
class ApplePeerCheckpointPersisterTest {
    @Test
    fun `nothing is written before the payee is registered`() =
        runTest {
            val book = ApplePeerCheckpointBook(FakePeerStorage())
            persister(book).onStatus(PeerCashOutStatus.ValidatingPayee(PeerPlatform.REVOLUT))

            assertTrue(book.all().isEmpty())
        }

    /**
     * The block read immediately before broadcasting, persisted before the send returns. Without it
     * a submission whose hash never came back leaves no trace at all, and the next launch has
     * nothing to look the order up from — so it would send again.
     */
    @Test
    fun `the pre-broadcast block floor is persisted before any hash exists`() =
        runTest {
            val book = ApplePeerCheckpointBook(FakePeerStorage())
            val persister = persister(book)
            persister.onStatus(PeerCashOutStatus.ValidatingPayee(PeerPlatform.REVOLUT, PayeeHash.parse(HASH)))
            persister.onStatus(PeerCashOutStatus.CreatingDeposit(AMOUNT, BLOCK))

            val stored = assertNotNull(book.get(ID))
            assertEquals(BLOCK.toLong(), stored.blockFloor)
            assertNull(stored.createDepositTxHash)
            assertEquals(PeerResumeAction.ReconcileSubmission, stored.resumeAction)
            assertTrue(stored.holdsUnescrowedFunds)
        }

    @Test
    fun `a submitted send resolves what it sent rather than sending again`() =
        runTest {
            val book = ApplePeerCheckpointBook(FakePeerStorage())
            val persister = persister(book)
            persister.onStatus(PeerCashOutStatus.ValidatingPayee(PeerPlatform.REVOLUT, PayeeHash.parse(HASH)))
            persister.onStatus(PeerCashOutStatus.CreatingDeposit(AMOUNT, BLOCK))
            persister.onStatus(PeerCashOutStatus.CreatingDeposit(AMOUNT, BLOCK, TX))

            val stored = assertNotNull(book.get(ID))
            assertEquals(PeerResumeAction.ResolveSubmittedDeposit(TX), stored.resumeAction)
        }

    /** Once the deposit exists the chain is the whole record, on any device and after a reinstall. */
    @Test
    fun `an indexed order retires its checkpoint`() =
        runTest {
            val book = ApplePeerCheckpointBook(FakePeerStorage())
            val persister = persister(book)
            persister.onStatus(PeerCashOutStatus.ValidatingPayee(PeerPlatform.REVOLUT, PayeeHash.parse(HASH)))
            persister.onStatus(PeerCashOutStatus.CreatingDeposit(AMOUNT, BLOCK, TX))
            persister.onStatus(PeerCashOutStatus.OrderLive(peerOrderSnapshot(remaining = AMOUNT)))

            assertTrue(book.all().isEmpty())
        }

    /**
     * A reverted send escrowed nothing: there is no order to resolve and no funds left reserved.
     * Keeping the record is what makes a failed attempt subtract its amount from the spendable
     * balance until the wallet is wiped, and makes every retry resolve the same reverted receipt.
     */
    @Test
    fun `a provably reverted send retires its checkpoint`() =
        runTest {
            val book = ApplePeerCheckpointBook(FakePeerStorage())
            val persister = persister(book)
            persister.onStatus(PeerCashOutStatus.ValidatingPayee(PeerPlatform.REVOLUT, PayeeHash.parse(HASH)))
            persister.onStatus(PeerCashOutStatus.CreatingDeposit(AMOUNT, BLOCK, TX))
            persister.onStatus(
                PeerCashOutStatus.Failed(
                    step = PeerCashOutStep.CREATING_DEPOSIT,
                    error =
                        PeerErrorCode.TRANSACTION_FAILED.asError(
                            recovery = PeerRecovery.InspectBaseTransaction(TX, "createDeposit"),
                        ),
                ),
            )

            assertTrue(book.all().isEmpty())
        }

    /** An outcome nobody can name settles nothing, so the record — and the reservation — survive. */
    @Test
    fun `an unknown submission outcome keeps its checkpoint`() =
        runTest {
            val book = ApplePeerCheckpointBook(FakePeerStorage())
            val persister = persister(book)
            persister.onStatus(PeerCashOutStatus.ValidatingPayee(PeerPlatform.REVOLUT, PayeeHash.parse(HASH)))
            persister.onStatus(PeerCashOutStatus.CreatingDeposit(AMOUNT, BLOCK))
            persister.onStatus(
                PeerCashOutStatus.Failed(
                    step = PeerCashOutStep.CREATING_DEPOSIT,
                    error = PeerErrorCode.TRANSACTION_SUBMISSION_UNKNOWN.asError(),
                ),
            )

            val stored = assertNotNull(book.get(ID))
            assertEquals(PeerResumeAction.ReconcileSubmission, stored.resumeAction)
            assertTrue(stored.holdsUnescrowedFunds)
        }

    /**
     * A failure before the send proves nothing was broadcast, so it is retired too — but only
     * because nothing is left to resolve, not because the failure said so.
     */
    @Test
    fun `a failure before any send leaves nothing to resolve`() =
        runTest {
            val book = ApplePeerCheckpointBook(FakePeerStorage())
            val persister = persister(book)
            persister.onStatus(PeerCashOutStatus.ValidatingPayee(PeerPlatform.REVOLUT, PayeeHash.parse(HASH)))
            persister.onStatus(
                PeerCashOutStatus.Failed(
                    step = PeerCashOutStep.APPROVING_USDC,
                    error = PeerErrorCode.TRANSACTION_FAILED.asError(),
                ),
            )

            assertTrue(book.all().isEmpty())
        }

    /** A resume writes on top of its own record; it must not lose the hashes already stored. */
    @Test
    fun `seeding from a stored checkpoint preserves what was already broadcast`() =
        runTest {
            val book = ApplePeerCheckpointBook(FakePeerStorage())
            val first = persister(book)
            first.onStatus(PeerCashOutStatus.ValidatingPayee(PeerPlatform.REVOLUT, PayeeHash.parse(HASH)))
            first.onStatus(PeerCashOutStatus.CreatingDeposit(AMOUNT, BLOCK, TX))

            val stored = assertNotNull(book.get(ID))
            val resumed = persister(book)
            resumed.seedFrom(stored)
            resumed.onStatus(PeerCashOutStatus.Idle)

            val after = assertNotNull(book.get(ID))
            assertEquals(TX, after.createDepositTxHash)
            assertEquals(BLOCK.toLong(), after.blockFloor)
            assertEquals(stored.createdAtMillis, after.createdAtMillis)
        }

    /**
     * A record belongs to exactly one attempt. Seeding from another's would put one attempt's
     * transaction hashes over another's amount, and resolving that resolves the wrong deposit.
     */
    @Test
    fun `a checkpoint belonging to another attempt is not adopted`() =
        runTest {
            val book = ApplePeerCheckpointBook(FakePeerStorage())
            val other = persister(book, PeerCashOutId.of(OTHER_ID))
            other.onStatus(PeerCashOutStatus.ValidatingPayee(PeerPlatform.REVOLUT, PayeeHash.parse(HASH)))
            other.onStatus(PeerCashOutStatus.CreatingDeposit(AMOUNT, BLOCK, TX))

            val mine = persister(book)
            mine.seedFrom(assertNotNull(book.get(PeerCashOutId.of(OTHER_ID))))
            mine.onStatus(PeerCashOutStatus.ValidatingPayee(PeerPlatform.REVOLUT, PayeeHash.parse(HASH)))

            assertNull(book.get(ID))
            assertEquals(TX, book.get(PeerCashOutId.of(OTHER_ID))?.createDepositTxHash)
        }

    private fun persister(
        book: ApplePeerCheckpointBook,
        id: PeerCashOutId = ID,
    ): ApplePeerCheckpointPersister =
        ApplePeerCheckpointPersister(
            checkpoints = book,
            id = id,
            request =
                PeerCashOutRequest(
                    platform = PeerPlatform.REVOLUT,
                    handle = PeerPlatform.REVOLUT.normalizeHandle("somerevtag"),
                    currencies = listOf(PeerCurrency.EUR),
                    amount = AMOUNT,
                ),
            nowMillis = { CREATED_AT },
        )

    private companion object {
        val ID: PeerCashOutId = PeerCashOutId.of("0123456789abcdef0123456789abcdef")
        const val OTHER_ID = "fedcba9876543210fedcba9876543210"
        val HASH = "0x" + "11".repeat(HASH_BYTES)
        const val HASH_BYTES = 32
        const val BLOCK = "33000000"
        const val CREATED_AT = 1_700_000_000_000L
        val AMOUNT: Usdc6 = Usdc6.ofMicros(20_000_000L)
        val TX: TxHash = TxHash.fromHex("0x" + "aa".repeat(TxHash.LEN))
    }
}
