// SPDX-License-Identifier: MIT OR Apache-2.0
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.evm.abi

import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.justzappit.evm.types.Address
import xyz.justzappit.evm.util.toHex

class KeccakParityTest {
    @Test
    fun emptyInputMatchesEthereumVector() {
        assertEquals(
            "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470",
            keccak256(byteArrayOf()).toHex(),
        )
    }

    @Test
    fun asciiInputMatchesEthereumVector() {
        assertEquals(
            "4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45",
            keccak256("abc".encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun selectorMatchesViem() {
        assertEquals("0x095ea7b3", Selector4.fromCanonicalSignature("approve(address,uint256)").hex)
    }

    @Test
    fun checksumAddressMatchesEip55() {
        assertEquals(
            "0x9858EfFD232B4033E47d90003D41EC34EcaEda94",
            Address.parse("0x9858effd232b4033e47d90003d41ec34ecaeda94").checksumHex,
        )
    }
}
