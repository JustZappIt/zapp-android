// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package co.electriccoin.zcash.ui.common.datasource

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

/**
 * Pins the rule that tells a claim's two long waits apart: a scan back to an old birthday runs for
 * many minutes and must not be cut off, while a scan that keeps restarting the same block batch
 * must not spin forever. See [failWhenScanStalls].
 *
 * `runTest` drives these in virtual time, so a six-minute window costs nothing to test.
 */
class GiftClaimScanStallTest {
    @Test
    fun `gives up once nothing has moved for the whole window`() =
        runTest {
            assertFailsWith<GiftCardScanStalledException> {
                failWhenScanStalls(scannedHeight = { 3_452_281L }, fraction = { 0.5f })
            }
        }

    @Test
    fun `does not give up one poll short of the window`() =
        runTest {
            var polls = 0
            assertFailsWith<GiftCardScanStalledException> {
                failWhenScanStalls(
                    scannedHeight = {
                        polls++
                        3_452_281L
                    },
                    fraction = { 0.5f },
                )
            }
            assertEquals(STALL_POLL_LIMIT + 1, polls)
        }

    @Test
    fun `never fires while the scanned height climbs`() =
        runTest {
            var height = 3_452_281L
            // Four times the window: a legitimate long scan must not be cut off.
            val outcome =
                withTimeoutOrNull(STALL_TIMEOUT * 4) {
                    failWhenScanStalls(
                        scannedHeight = {
                            height += 1
                            height
                        },
                        fraction = { 0f },
                    )
                }
            assertNull(outcome)
        }

    @Test
    fun `never fires while only the fraction climbs`() =
        runTest {
            var fraction = 0f
            val outcome =
                withTimeoutOrNull(STALL_TIMEOUT * 4) {
                    failWhenScanStalls(
                        scannedHeight = { null },
                        fraction = {
                            fraction += 0.001f
                            fraction
                        },
                    )
                }
            assertNull(outcome)
        }

    @Test
    fun `movement resets the count`() =
        runTest {
            var polls = 0
            var height = 3_452_281L
            assertFailsWith<GiftCardScanStalledException> {
                failWhenScanStalls(
                    scannedHeight = {
                        // One batch lands late in a window; the idle count must start over.
                        if (polls++ == STALL_POLL_LIMIT - 1) height += 1000
                        height
                    },
                    fraction = { 0f },
                )
            }
            assertEquals(1 + (STALL_POLL_LIMIT - 1) + STALL_POLL_LIMIT, polls)
        }

    @Test
    fun `the first height after null counts as movement`() =
        runTest {
            var polls = 0
            assertFailsWith<GiftCardScanStalledException> {
                failWhenScanStalls(
                    scannedHeight = { if (polls++ == 0) null else 3_452_281L },
                    fraction = { 0f },
                )
            }
            // Nothing scanned yet sits below every real height, so the first one is progress.
            assertEquals(STALL_POLL_LIMIT + 2, polls)
        }

    @Test
    fun `a regressing height still stalls`() =
        runTest {
            var height = 3_452_877L
            assertFailsWith<GiftCardScanStalledException> {
                failWhenScanStalls(
                    scannedHeight = {
                        // The failing batch restarting: each attempt reaches less far.
                        height -= 10
                        height
                    },
                    fraction = { 0f },
                )
            }
        }

    @Test
    fun `the window is what its documentation claims`() {
        assertEquals(6.minutes, STALL_TIMEOUT)
        assertEquals(36, STALL_POLL_LIMIT)
    }
}
