// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.abi

import xyz.justzappit.evm.math.bigIntegerValueOf
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.hexToBytes
import xyz.justzappit.evm.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Byte-for-byte fixture from EscrowV2 deposit 3788 on Base mainnet
 * (tx 0x7af143a748eaa9e69b4b883990fa4a0f84b2e1eaf59978255fce092683e5be47), with the ERC-8021
 * attribution suffix that follows the ABI encoding stripped. A wrong offset here escrows funds
 * against a malformed deposit, so this runs against real calldata rather than a hand-built vector.
 */
class AbiTupleParityTest {
    @Test
    fun `createDeposit payload matches live base mainnet transaction`() {
        val encoded =
            AbiEncoder.encodeFunctionCall(
                CREATE_DEPOSIT_SIGNATURE,
                listOf(
                    AbiTuple(
                        listOf(
                            AbiAddress(Address.parse("0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913")),
                            AbiUint(bigIntegerValueOf(612_000_000L)),
                            AbiTuple(
                                listOf(
                                    AbiUint(bigIntegerValueOf(1_000_000L)),
                                    AbiUint(bigIntegerValueOf(612_000_000L)),
                                ),
                            ),
                            AbiArray(listOf(AbiBytes32(REVOLUT_HASH.hexToBytes()))),
                            AbiArray(
                                listOf(
                                    AbiTuple(
                                        listOf(
                                            AbiAddress(Address.parse("0x396D31055Db28C0C6f36e8b36f18FE7227248a97")),
                                            AbiBytes32(PAYEE_HASH.hexToBytes()),
                                            AbiBytes(ByteArray(0)),
                                        ),
                                    ),
                                ),
                            ),
                            AbiArray(
                                listOf(
                                    AbiArray(
                                        listOf(
                                            AbiTuple(
                                                listOf(
                                                    AbiBytes32(EUR_HASH.hexToBytes()),
                                                    AbiUint(bigIntegerValueOf(1L)),
                                                    AbiTuple(
                                                        listOf(
                                                            AbiAddress(Address.parse(ORACLE_ADAPTER)),
                                                            AbiBytes(ADAPTER_CONFIG),
                                                            AbiInt16(0),
                                                            AbiUint32(86_400L),
                                                        ),
                                                    ),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                            AbiAddress(Address.ZERO),
                            AbiAddress(Address.parse("0x83671606454fa72ba1e2831e18c5090d25629414")),
                            AbiBool(false),
                        ),
                    ),
                ),
            )
        assertEquals(LIVE_CALLDATA.lowercase(), "0x" + encoded.toHex())
    }

    private companion object {
        const val CREATE_DEPOSIT_SIGNATURE =
            "createDeposit((address,uint256,(uint256,uint256),bytes32[],(address,bytes32,bytes)[]," +
                "(bytes32,uint256,(address,bytes,int16,uint32))[][],address,address,bool))"
        const val REVOLUT_HASH = "0x617f88ab82b5c1b014c539f7e75121427f0bb50a4c58b187a238531e7d58605d"
        const val PAYEE_HASH = "0x4f8588e2ba399fa3b1b99e5f0690cadbb2e22608b6429d643e60ae94dfdf7afb"
        const val EUR_HASH = "0xfff16d60be267153303bbfa66e593fb8d06e24ea5ef24b6acca5224c2ca6b907"
        const val ORACLE_ADAPTER = "0xfc81d1b5841e697973af3072fc8e03af76cb39ef"
        const val EUR_FEED = "0xc91D87E81faB8f93699ECf7Ee9B44D11e1D53F0F"

        /** The adapter's `(feed, invert)` config, encoded as the bytes blob the tuple carries. */
        val ADAPTER_CONFIG: ByteArray =
            AbiEncoder.encode(listOf(AbiAddress(Address.parse(EUR_FEED)), AbiBool(true)))

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
