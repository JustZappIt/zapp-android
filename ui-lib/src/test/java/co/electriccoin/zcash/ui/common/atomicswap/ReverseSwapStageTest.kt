// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.atomicswap

import xyz.justzappit.offramp.atomicswap.ReversePhase
import kotlin.test.Test
import kotlin.test.assertEquals

class ReverseSwapStageTest {
    @Test
    fun `every phase a reverse swap can be in says what it is doing`() {
        assertEquals(ReversePhase.entries.toSet(), PHASE_LABELS.keys)
    }
}
