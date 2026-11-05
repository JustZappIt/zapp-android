// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.deposits

import kotlinx.coroutines.test.runTest
import xyz.justzappit.evm.deposits.DepositsDomain
import xyz.justzappit.evm.deposits.ReceiveAuthorization
import xyz.justzappit.evm.rpc.TransactionStatus
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.atomicswap.NoteCommitment
import xyz.justzappit.offramp.atomicswap.SwapNote
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DepositorTest {
    private val world = World()

    @Test
    fun `a new wallet shows its first address, and one unused window says so`() =
        runTest {
            val pass = world.pass()

            assertEquals(0, pass.scan?.showing)
            assertEquals(1, pass.scan?.windows)
            assertTrue(pass.isScanned)
            assertEquals(emptyList(), pass.records)
            assertNull(pass.nextPoll)
            assertEquals(1, world.chain.scans)
            assertEquals(0, world.notes.made)
        }

    @Test
    fun `a wallet restored from its seed finds every used address, a window at a time until one is unused`() =
        runTest {
            world.chain.deposited += setOf(0, 1, 2)
            world.chain.balances[25] = 1_000_000

            val pass = world.pass()

            val scan = checkNotNull(pass.scan)
            assertEquals(listOf(0, 1, 2, 25), scan.used.map { it.index })
            assertEquals(3, scan.windows)
            assertEquals(3, scan.showing)
            assertEquals(3, world.chain.scans)
            assertEquals(scan, world.store.lastScan)
            assertEquals((0 until 3 * GAP).map(world.keys::address).toSet(), world.depositor.ownAddresses())
        }

    @Test
    fun `an address under the minimum stays the one shown, and isn't signed for`() =
        runTest {
            world.chain.balances[0] = 1_999_999

            val pass = world.pass()

            assertEquals(0, pass.scan?.showing)
            assertEquals(emptyList(), world.relayer.shields)
            assertNull(pass.nextPoll)
        }

    @Test
    fun `an address holding the minimum is signed for, its whole balance, and kept before it's posted`() =
        runTest {
            world.chain.balances[0] = 5_000_000
            world.relayer.answers += DepositAnswer.Sent(listOf(tx(1)))
            var keptWhenPosted: DepositRecord? = null
            world.relayer.onPost = { keptWhenPosted = world.store.kept.firstOrNull() }

            val pass = world.pass()

            val request = world.relayer.shields.single()
            assertEquals(DepositState.SIGNED, keptWhenPosted?.state)
            assertEquals(request, keptWhenPosted?.shield)
            assertEquals(SEPOLIA_PIN.chainId, request.chainId)
            assertEquals(SEPOLIA_PIN.contract, request.contract)
            assertEquals(world.keys.address(0), request.from)
            assertEquals(Usdc6.ofMicros(5_000_000), request.value)
            assertEquals(0, request.validAfter)
            assertEquals(NOW + 3_600, request.validBefore)
            assertEquals(Usdc6.ofMicros(105_000), request.fee)
            assertEquals(FakeNotes.shield(0, NOW + 3_600).wire(), request.note)
            val record = pass.records.single()
            assertEquals(DepositState.SENT, record.state)
            assertEquals(listOf(tx(1)), record.transactions)
            assertEquals("shield-0-${NOW + 3_600}", record.id)
            assertEquals(FOLLOW_EVERY, pass.nextPoll)
        }

    @Test
    fun `the signature is the address's own, over an authorization whose nonce commits to the note, fee and account`() =
        runTest {
            world.chain.balances[4] = 5_000_000
            world.chain.balances[0] = 1

            world.pass()

            val request = world.relayer.shields.single()
            val nonce =
                DepositsDomain(SEPOLIA_PIN.chainId, SEPOLIA_PIN.contract)
                    .depositNonce(FakeNotes.shield(4, NOW + 3_600), units(105_000), SEPOLIA_PIN.relayer)
            val from = world.keys.address(4)
            val authorization =
                ReceiveAuthorization(from, SEPOLIA_PIN.contract, units(5_000_000), 0, NOW + 3_600, nonce)
            assertEquals(from, signer(world.chain.domain.receiveWithAuthorization(authorization), request.signature))
            assertEquals(
                "0x" + nonce.toHex(),
                world.store.kept
                    .single()
                    .nonce
            )
        }

    @Test
    fun `a fee over the cap is never signed for, and says so`() =
        runTest {
            world.relayer.terms = { sepoliaTerms(fee = 2_500_000) }
            world.chain.balances[0] = 5_000_000

            val pass = world.pass()

            assertTrue(pass.isFeeOverCap)
            assertEquals(emptyList(), pass.records)
            assertEquals(0, world.notes.made)
            assertEquals(SIGN_RETRY, pass.nextPoll)
        }

    @Test
    fun `nothing is signed under a token domain that isn't the pinned token's`() =
        runTest {
            world.chain.isDomainPinned = false
            world.chain.balances[0] = 5_000_000

            val pass = world.pass()

            assertEquals(emptyList(), pass.records)
            assertEquals(emptyList(), world.relayer.shields)
            assertEquals(SIGN_RETRY, pass.nextPoll)
            assertTrue(world.logs.any { "address 0 wasn't signed for" in it })
        }

    @Test
    fun `a deposit lands only once the pinned contract's event names its address and note`() =
        runTest {
            world.chain.balances[0] = 5_000_000
            world.relayer.answers += DepositAnswer.Sent(listOf(tx(1)))
            world.pass()
            val sent = world.store.kept.single()
            world.chain.statuses[tx(1)] = TransactionStatus.CONFIRMED
            world.chain.events[tx(1)] =
                listOf(event(sent, from = world.keys.address(1)), event(sent, note = NoteCommitment.of(ByteArray(32))))

            world.pass()

            val unbelieved = world.store.kept.single()
            assertEquals(DepositState.SIGNED, unbelieved.state)
            assertEquals(emptyList(), unbelieved.transactions)
            assertEquals(NOW + 15, unbelieved.retryAt)
            world.chain.landed(tx(1), sent)
            world.relayer.answers += DepositAnswer.Sent(listOf(tx(1)))
            world.now += 15

            world.pass()
            world.pass()

            val landed = world.store.kept.single()
            assertEquals(DepositState.LANDED, landed.state)
            val landing = DepositLanding(tx(1), Usdc6.ofMicros(4_895_000), Usdc6.ofMicros(105_000), NOW + 15)
            assertEquals(landing, landed.landing)
            assertEquals(units(4_882_763), landed.arrives)
        }

    @Test
    fun `a transaction not mined within a minute is posted again, the same bytes`() =
        runTest {
            world.chain.balances[0] = 5_000_000
            world.relayer.answers += DepositAnswer.Sent(listOf(tx(1)))
            world.pass()
            world.chain.statuses[tx(1)] = TransactionStatus.PENDING

            world.now += 59
            world.pass()
            assertEquals(1, world.relayer.shields.size)
            world.now += 1
            world.relayer.answers += DepositAnswer.Retry("busy", null, isWaiting = true)
            world.pass()

            assertEquals(2, world.relayer.shields.size)
            assertEquals(world.relayer.shields[0], world.relayer.shields[1])
            // Named before, it stays named, whatever the answer: never signed again while that transaction may land.
            val record = world.store.kept.single()
            assertEquals(DepositState.SENT, record.state)
            assertEquals(NOW + 60, record.postedAt)
        }

    @Test
    fun `a reverted deposit holds its address back an hour`() =
        runTest {
            world.chain.balances[0] = 5_000_000
            world.relayer.answers += DepositAnswer.Sent(listOf(tx(1)))
            world.pass()
            world.chain.statuses[tx(1)] = TransactionStatus.REVERTED

            world.pass()
            world.now += 59.minutes.inWholeSeconds
            world.pass()

            assertEquals(
                DepositState.REVERTED,
                world.store.kept
                    .single()
                    .state
            )
            assertEquals(1, world.relayer.shields.size)
            world.now += 1.minutes.inWholeSeconds
            world.pass()
            assertEquals(2, world.relayer.shields.size)
        }

    @Test
    fun `a refusal is signed again once, and a second leaves the address alone an hour unless something changes`() =
        runTest {
            world.chain.balances[0] = 5_000_000
            world.relayer.answers += DepositAnswer.Refused(400, "a fee of 105000 is less than the relayer's")
            world.relayer.answers += DepositAnswer.Refused(400, "the deposit address is sanctioned")

            world.pass()

            val (again, first) = world.store.kept
            assertEquals(DepositState.REPLACED, first.state)
            assertEquals(DepositState.REFUSED, again.state)
            assertTrue(again.afterRefusal)
            assertEquals(2, world.relayer.shields.size)
            assertEquals(2, world.notes.made)
            world.now += 10.minutes.inWholeSeconds
            world.pass()
            assertEquals(2, world.relayer.shields.size)
            world.chain.balances[0] = 6_000_000
            world.pass()
            assertEquals(3, world.relayer.shields.size)
        }

    @Test
    fun `a refused address is signed for again once the hour is over, or the terms change`() =
        runTest {
            world.chain.balances[0] = 5_000_000
            repeat(2) { world.relayer.answers += DepositAnswer.Refused(400, "no") }
            world.pass()
            world.relayer.terms = { sepoliaTerms(fee = 90_000) }
            world.pass()
            assertEquals(3, world.relayer.shields.size)

            val other = World()
            other.chain.balances[0] = 5_000_000
            repeat(2) { other.relayer.answers += DepositAnswer.Refused(400, "no") }
            other.pass()
            other.now += 60.minutes.inWholeSeconds
            other.pass()
            assertEquals(3, other.relayer.shields.size)
        }

    @Test
    fun `a conflict naming a transaction is followed, and believed for any note the address's requests pay`() =
        runTest {
            world.chain.balances[0] = 5_000_000
            world.relayer.answers += DepositAnswer.Retry("busy", null, isWaiting = true)
            world.pass()
            val first = world.store.kept.single()
            // Close to running out, it's signed again: the one before may still be under way at the relayer.
            world.now = first.expiresAt - 16.minutes.inWholeSeconds + 1
            world.relayer.answers += DepositAnswer.Conflict(listOf(tx(2)))
            world.pass()
            val (second, replaced) = world.store.kept
            assertEquals(DepositState.REPLACED, replaced.state)
            assertEquals(DepositState.SENT, second.state)
            assertTrue(second.conflict)
            world.chain.landed(tx(2), replaced)

            world.pass()

            assertEquals(DepositState.LANDED, world.store.record(second.id).state)
        }

    @Test
    fun `a conflict naming nothing lands once the authorization is used`() =
        runTest {
            world.chain.balances[0] = 5_000_000
            world.chain.isAuthorizationUsed = { owner, _ -> owner == world.keys.address(0) }
            world.relayer.answers += DepositAnswer.Conflict(emptyList())

            world.pass()

            val record = world.store.kept.single()
            assertEquals(DepositState.LANDED, record.state)
            assertEquals(DepositLanding(null, Usdc6.ofMicros(4_895_000), Usdc6.ofMicros(105_000), NOW), record.landing)
        }

    @Test
    fun `a conflict naming nothing ends a request whose funds another deposit took, and waits otherwise`() =
        runTest {
            world.chain.balances[0] = 5_000_000
            world.relayer.answers += DepositAnswer.Conflict(emptyList())
            world.pass()
            assertEquals(
                DepositState.SIGNED,
                world.store.kept
                    .single()
                    .state
            )
            assertEquals(
                NOW + 15,
                world.store.kept
                    .single()
                    .retryAt
            )

            world.now += 15
            world.relayer.answers += DepositAnswer.Conflict(emptyList())
            world.relayer.onPost = { world.chain.balances[0] = 0 }
            world.pass()

            assertEquals(
                DepositState.REPLACED,
                world.store.kept
                    .single()
                    .state
            )
        }

    @Test
    fun `no answer is posted again with backoff from 15 s to 5 min, never sooner than the relayer asked`() =
        runTest {
            world.chain.balances[0] = 5_000_000
            val waits = mutableListOf<Long>()
            repeat(7) {
                world.relayer.answers += DepositAnswer.Retry("unreachable", null, isWaiting = true)
                world.pass()
                val record = world.store.kept.single()
                waits += checkNotNull(record.retryAt) - world.now
                assertTrue(record.waiting)
                world.now = checkNotNull(record.retryAt)
            }
            assertEquals(listOf(15L, 30, 60, 120, 240, 300, 300), waits)
            assertEquals(7, world.relayer.shields.size)
            assertEquals(
                1,
                world.relayer.shields
                    .toSet()
                    .size
            )

            world.relayer.answers += DepositAnswer.Retry("gas is above the cap", 30.minutes, isWaiting = true)
            world.pass()
            assertEquals(
                world.now + 30.minutes.inWholeSeconds,
                world.store.kept
                    .single()
                    .retryAt
            )
            world.now += 10
            val pass = world.pass()
            assertEquals(8, world.relayer.shields.size)
            assertEquals((30.minutes - 10.seconds), pass.nextPoll)
        }

    @Test
    fun `a request is signed again once its validity runs low, or the fee's honor window is over`() =
        runTest {
            world.chain.balances[0] = 5_000_000
            world.relayer.answers += DepositAnswer.Retry("busy", null, isWaiting = false)
            world.pass()
            // With 16 minutes left it's posted again as it is; with less, it's signed again.
            world.now = NOW + 3_600 - 16 * 60
            world.pass()
            assertEquals(2, world.relayer.shields.size)
            assertEquals(1, world.store.kept.size)
            world.now += 1
            world.pass()
            val (resigned, stale) = world.store.kept
            assertEquals(DepositState.REPLACED, stale.state)
            assertEquals(world.now + 3_600, resigned.shield?.validBefore)
            assertEquals(3, world.relayer.shields.size)
            assertEquals(resigned.shield, world.relayer.shields.last())

            val fees = World()
            fees.relayer.terms = { sepoliaTerms(feeExpiresAt = NOW + 600) }
            fees.chain.balances[0] = 5_000_000
            fees.pass()
            fees.now = NOW + 599
            fees.pass()
            assertEquals(1, fees.store.kept.size)
            fees.now = NOW + 600
            fees.relayer.terms = { sepoliaTerms(feeExpiresAt = NOW + 1_200) }
            fees.pass()
            assertEquals(listOf(DepositState.SIGNED, DepositState.REPLACED), fees.store.kept.map { it.state })
        }

    @Test
    fun `a named transaction gone past its authorization expires`() =
        runTest {
            world.chain.balances[0] = 5_000_000
            world.relayer.answers += DepositAnswer.Sent(listOf(tx(1)))
            world.pass()
            world.chain.balances[0] = 0
            world.now = NOW + 3_600 + 60
            world.pass()
            assertEquals(
                DepositState.SENT,
                world.store.kept
                    .single()
                    .state
            )

            world.now += 1
            world.pass()

            assertEquals(
                DepositState.EXPIRED,
                world.store.kept
                    .single()
                    .state
            )
        }

    @Test
    fun `nothing goes to a relayer that takes deposits for another deployment, and kept bytes wait`() =
        runTest {
            world.chain.balances[0] = 5_000_000
            world.relayer.answers += DepositAnswer.Retry("busy", null, isWaiting = true)
            world.pass()
            world.relayer.terms = { relayerTerms(MAINNET_TERMS) }
            world.chain.balances[1] = 5_000_000
            world.now += 60

            val pass = world.pass()

            assertEquals(DepositTermsRead.OtherDeployment, pass.terms)
            assertEquals(1, world.relayer.shields.size)
            assertEquals(1, world.store.kept.size)
            assertEquals(
                DepositState.SIGNED,
                world.store.kept
                    .single()
                    .state
            )
        }

    @Test
    fun `terms that don't read stop new signings but not kept bytes, and bring the next pass in a minute`() =
        runTest {
            world.chain.balances[0] = 5_000_000
            world.relayer.answers += DepositAnswer.Retry("busy", null, isWaiting = true)
            world.pass()
            world.relayer.terms = { error("the relayer is down") }
            world.chain.balances[1] = 5_000_000
            world.now += 15
            world.relayer.answers += DepositAnswer.Sent(listOf(tx(1)))

            val pass = world.pass()

            assertNull(pass.terms)
            assertEquals(2, world.relayer.shields.size)
            assertEquals(1, world.store.kept.size)
            assertEquals(FOLLOW_EVERY, pass.nextPoll)
            world.chain.landed(tx(1), world.store.kept.single())
            assertEquals(SIGN_RETRY, world.pass().nextPoll)
        }

    @Test
    fun `without new signings, deposits under way still go on`() =
        runTest {
            world.chain.balances[0] = 5_000_000
            world.relayer.answers += DepositAnswer.Sent(listOf(tx(1)))
            world.pass()
            world.chain.landed(tx(1), world.store.kept.single())
            world.chain.balances[1] = 5_000_000

            val pass = world.depositor.pass(signNew = false)

            assertEquals(DepositState.LANDED, pass.records.single().state)
            assertEquals(1, world.relayer.shields.size)
            assertNull(pass.nextPoll)
        }

    @Test
    fun `a chain that doesn't read shows the last scan, and follows nothing`() =
        runTest {
            world.chain.balances[0] = 5_000_000
            world.relayer.answers += DepositAnswer.Sent(listOf(tx(1)))
            world.pass()
            world.chain.isReadable = false
            world.now += 120

            val pass = world.pass()

            assertFalse(pass.isScanned)
            assertEquals(world.store.lastScan, pass.scan)
            assertEquals(1, world.relayer.shields.size)
            assertEquals(DepositState.SENT, pass.records.single().state)
        }

    @Test
    fun `a request kept for another wallet's address is left alone`() =
        runTest {
            world.chain.balances[0] = 5_000_000
            world.relayer.answers += DepositAnswer.Retry("busy", null, isWaiting = true)
            world.pass()
            val record = world.store.kept.single()
            val foreign = Address.parse("0x" + "42".repeat(20))
            world.store.kept[0] = record.copy(shield = record.shield?.copy(from = foreign))
            world.now += 15

            world.pass()

            assertEquals(1, world.relayer.shields.size)
            assertTrue(world.logs.none { "didn't move on" in it })
        }

    @Test
    fun `a vault Railgun sent money back to is rescued, signed with the contract's nonce, kept, then posted`() =
        runTest {
            world.chain.deposited += 0
            world.chain.vaults[0] = 700_000
            world.chain.rescueNonces[0] = 2
            world.pass()
            assertEquals(listOf(DepositRescuable(0, units(700_000), units(100_000))), world.depositor.rescuable())
            world.relayer.answers += DepositAnswer.Sent(listOf(tx(9)))
            var keptWhenPosted: DepositRecord? = null
            world.relayer.onPost = { keptWhenPosted = world.store.kept.firstOrNull() }

            val record = world.depositor.rescue(0)

            val request = world.relayer.rescues.single()
            assertEquals(request, keptWhenPosted?.rescue)
            assertEquals(DepositState.SENT, record.state)
            assertEquals(DepositKind.RESCUE, record.kind)
            assertEquals(2, request.nonce)
            assertEquals(NOW + 3_600, request.deadline)
            assertEquals(Usdc6.ofMicros(100_000), request.fee)
            assertEquals("rescue-0-2-${NOW + 3_600}", record.id)
            val digest =
                DepositsDomain(SEPOLIA_PIN.chainId, SEPOLIA_PIN.contract).rescue(
                    world.keys.address(0),
                    FakeNotes.rescue(0, 2),
                    SEPOLIA_PIN.relayer,
                    units(100_000),
                    2,
                    NOW + 3_600,
                )
            assertEquals(world.keys.address(0), signer(digest, request.signature))
            // One on its way is the only one.
            val again = assertFailsWith<DepositsException> { world.depositor.rescue(0) }
            assertEquals(DepositProblem.NOTHING_TO_RESCUE, again.problem)
        }

    @Test
    fun `a vault holding no more than the fee is neither rescuable nor rescued`() =
        runTest {
            world.chain.deposited += 0
            world.chain.vaults[0] = 100_000
            world.pass()

            assertEquals(emptyList(), world.depositor.rescuable())
            val refused = assertFailsWith<DepositsException> { world.depositor.rescue(0) }
            assertEquals(DepositProblem.NOTHING_TO_RESCUE, refused.problem)
            assertEquals(emptyList(), world.relayer.rescues)
        }

    @Test
    fun `a request too large for the relayer is never kept, and no record or request tells its secrets`() {
        val huge =
            ShieldDeposit(
                chainId = SEPOLIA_PIN.chainId,
                contract = SEPOLIA_PIN.contract,
                from = SEPOLIA_PIN.relayer,
                value = Usdc6.ofMicros(1),
                validAfter = 0,
                validBefore = 1,
                note = SwapNote("0x", emptyList(), "0x"),
                fee = Usdc6.ofMicros(0),
                signature = "0x" + "ab".repeat(9_000),
            )
        assertFailsWith<IllegalStateException> { requireFits(huge, ShieldDeposit.serializer()) }
        val record =
            DepositRecord(
                id = "shield-0-1",
                kind = DepositKind.SHIELD,
                index = 0,
                state = DepositState.SIGNED,
                createdAt = NOW,
                value = Usdc6.ofMicros(1),
                terms = SignedDepositTerms(Usdc6.ofMicros(0), 10, Usdc6.ofMicros(1), 86_400),
                shield = huge,
            )
        assertEquals("DepositRecord(SHIELD of address 0, SIGNED)", record.toString())
        assertFailsWith<IllegalArgumentException> { record.copy(kind = DepositKind.RESCUE) }
    }

    private class World {
        var now = NOW
        val keys = FakeKeys()
        val chain = FakeChain(keys)
        val notes = FakeNotes()
        val store = FakeStore()
        val relayer = FakeRelayer()
        val logs = mutableListOf<String>()
        val depositor = Depositor(SEPOLIA_PIN, relayer, chain, keys, notes, store, { now }, { logs += it })

        suspend fun pass(): DepositPass = depositor.pass(signNew = true)
    }
}
