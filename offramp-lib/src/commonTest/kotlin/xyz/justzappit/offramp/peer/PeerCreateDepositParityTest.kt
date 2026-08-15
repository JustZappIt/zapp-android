// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.peer

import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.rpc.EvmLog
import xyz.justzappit.evm.rpc.TransactionReceipt
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.toHex
import xyz.justzappit.offramp.p2p.Usdc6
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * EscrowV2 deposit 3788 on Base mainnet, tx
 * 0x7af143a748eaa9e69b4b883990fa4a0f84b2e1eaf59978255fce092683e5be47, with the ERC-8021 attribution
 * suffix that follows the ABI encoding stripped.
 *
 * `AbiTupleParityTest` pins the same payload against the encoder; this pins it against the builder
 * that actually broadcasts. Both are needed: a component reordered inside [PeerDepositParams.toAbiArg]
 * produces a well-formed transaction that escrows funds against a deposit no buyer can fill, and the
 * encoder-level test cannot see it.
 *
 * Everything the deposit points at rides along — the Revolut payment-method hash, the EUR code hash
 * and feed, the oracle adapter, the staleness bound — so a wrong constant fails here rather than on
 * chain.
 */
class PeerCreateDepositParityTest {
    @Test
    fun `createDeposit calldata matches the live base mainnet transaction`() {
        val params =
            PeerDepositParams(
                token = Address.parse(USDC),
                amount = Usdc6.ofMicros(bigIntegerValueOf(612_000_000L)),
                platform = PeerPlatform.REVOLUT,
                payeeHash = PayeeHash.parse(PAYEE_HASH),
                currencies = listOf(PeerCurrency.EUR),
                gatingService = Address.parse(GATING_SERVICE),
                oracleAdapter = Address.parse(ORACLE_ADAPTER),
                intentAmountMin = Usdc6.ofMicros(bigIntegerValueOf(1_000_000L)),
                // The live deposit named a guardian; ours default to none, which the deposit survey
                // showed costs nothing. Passed explicitly so the vector stays byte-comparable.
                intentGuardian = Address.parse(INTENT_GUARDIAN),
            )

        assertEquals(LIVE_CALLDATA, "0x" + PeerEscrowCalls.createDepositCalldata(params).toHex())
    }

    /** Read off the live log rather than re-derived, which would only restate the implementation. */
    @Test
    fun `the DepositReceived topic matches the one the escrow emitted`() {
        assertEquals(LIVE_DEPOSIT_RECEIVED_TOPIC, PeerDepositReceipt.DEPOSIT_RECEIVED_TOPIC)
    }

    @Test
    fun `the deposit id is read out of the live receipt`() {
        val id = PeerDepositReceipt.depositIdFrom(liveReceipt(), Address.parse(ESCROW))

        assertEquals(PeerDepositId.of(Address.parse(ESCROW), bigIntegerValueOf(3788L)), id)
    }

    /** A log from another contract with the same topic must not be mistaken for ours. */
    @Test
    fun `a matching topic from a different contract is ignored`() {
        val foreign = liveReceipt().let { it.copy(logs = it.logs.map { log -> log.copy(address = USDC) }) }

        assertEquals(null, PeerDepositReceipt.depositIdFrom(foreign, Address.parse(ESCROW)))
    }

    private fun liveReceipt() =
        TransactionReceipt(
            transactionHash = TX_HASH,
            blockNumber = "0x2071b3b",
            status = "0x1",
            gasUsed = "0x0",
            logs =
                listOf(
                    EvmLog(
                        address = ESCROW,
                        topics =
                            listOf(
                                LIVE_DEPOSIT_RECEIVED_TOPIC,
                                "0x0000000000000000000000000000000000000000000000000000000000000ecc",
                            ),
                        data = "0x",
                        blockNumber = "0x2071b3b",
                        transactionHash = TX_HASH,
                        logIndex = "0x0",
                    ),
                ),
        )

    private companion object {
        const val TX_HASH = "0x7af143a748eaa9e69b4b883990fa4a0f84b2e1eaf59978255fce092683e5be47"
        const val ESCROW = "0x777777779d229cdf3110e9de47943791c26300ef"
        const val USDC = "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"
        const val GATING_SERVICE = "0x396D31055Db28C0C6f36e8b36f18FE7227248a97"
        const val ORACLE_ADAPTER = "0xfc81d1b5841e697973af3072fc8e03af76cb39ef"
        const val INTENT_GUARDIAN = "0x83671606454fa72ba1e2831e18c5090d25629414"
        const val PAYEE_HASH = "0x4f8588e2ba399fa3b1b99e5f0690cadbb2e22608b6429d643e60ae94dfdf7afb"

        /** `topics[0]` of the escrow's first log in [TX_HASH]. */
        const val LIVE_DEPOSIT_RECEIVED_TOPIC =
            "0x1236dbdc184b6c8721974cce53dabb6018679bca9a43784ab2ad71bcdb1d7dd1"

        const val LIVE_CALLDATA =
            "0xab3532c8" +
                "0000000000000000000000000000000000000000000000000000000000000020" +
                "000000000000000000000000833589fcd6edb6e08f4c7c32d4f71b54bda02913" +
                "00000000000000000000000000000000000000000000000000000000247a6100" +
                "00000000000000000000000000000000000000000000000000000000000f4240" +
                "00000000000000000000000000000000000000000000000000000000247a6100" +
                "0000000000000000000000000000000000000000000000000000000000000140" +
                "0000000000000000000000000000000000000000000000000000000000000180" +
                "0000000000000000000000000000000000000000000000000000000000000240" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "00000000000000000000000083671606454fa72ba1e2831e18c5090d25629414" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000001" +
                "617f88ab82b5c1b014c539f7e75121427f0bb50a4c58b187a238531e7d58605d" +
                "0000000000000000000000000000000000000000000000000000000000000001" +
                "0000000000000000000000000000000000000000000000000000000000000020" +
                "000000000000000000000000396d31055db28c0c6f36e8b36f18fe7227248a97" +
                "4f8588e2ba399fa3b1b99e5f0690cadbb2e22608b6429d643e60ae94dfdf7afb" +
                "0000000000000000000000000000000000000000000000000000000000000060" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000001" +
                "0000000000000000000000000000000000000000000000000000000000000020" +
                "0000000000000000000000000000000000000000000000000000000000000001" +
                "0000000000000000000000000000000000000000000000000000000000000020" +
                "fff16d60be267153303bbfa66e593fb8d06e24ea5ef24b6acca5224c2ca6b907" +
                "0000000000000000000000000000000000000000000000000000000000000001" +
                "0000000000000000000000000000000000000000000000000000000000000060" +
                "000000000000000000000000fc81d1b5841e697973af3072fc8e03af76cb39ef" +
                "0000000000000000000000000000000000000000000000000000000000000080" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000015180" +
                "0000000000000000000000000000000000000000000000000000000000000040" +
                "000000000000000000000000c91d87e81fab8f93699ecf7ee9b44d11e1d53f0f" +
                "0000000000000000000000000000000000000000000000000000000000000001"
    }
}
