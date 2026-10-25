// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2025-2026 The Zapp Contributors

package xyz.justzappit.offramp.deposits

import kotlinx.coroutines.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The lowest index never shielded from: the first whose `deposited` is false. One under the minimum stays shown. */
internal fun showing(used: List<ScannedAddress>): Int {
    val shielded = used.filter { it.deposited }.map { it.index }.toSet()
    return generateSequence(0) { it + 1 }.first { it !in shielded }
}

/** Whether [address] is due a deposit: it holds the minimum, none of its requests is under way, and it isn't paused. */
internal fun due(
    records: List<DepositRecord>,
    address: ScannedAddress,
    terms: DepositTerms,
    now: Long,
): Boolean =
    address.balance.micros >= terms.minValue &&
        records.none { it.index == address.index && !it.state.isFinished } &&
        !paused(records, address, terms, now)

/**
 * Whether [address] waits before it's signed for again: its newest shield was refused within the hour, for the same
 * balance under the same terms, or reverted within the hour. Signing into the same refusal every pass would go
 * nowhere, and the relayer holds an address back for an hour after a deposit of it reverted.
 */
internal fun paused(
    records: List<DepositRecord>,
    address: ScannedAddress,
    terms: DepositTerms,
    now: Long,
): Boolean {
    val newest = records.firstOrNull { it.index == address.index && it.kind == DepositKind.SHIELD } ?: return false
    val since = now - (newest.endedAt ?: 0)
    return when (newest.state) {
        DepositState.REFUSED -> {
            since < PAUSE_SECONDS && newest.value == address.balance && newest.terms.isSame(terms)
        }

        DepositState.REVERTED -> {
            since < PAUSE_SECONDS
        }

        else -> {
            false
        }
    }
}

/** When a pass should run again for [records]: soon while one is posted, at its retry while one waits to go again. */
internal fun nextPoll(
    records: List<DepositRecord>,
    now: Long,
): Duration? =
    records
        .mapNotNull { record ->
            when (record.state) {
                DepositState.SENT -> FOLLOW_EVERY
                DepositState.SIGNED -> maxOf(1L, (record.retryAt ?: (now + BACKOFF_SECONDS)) - now).seconds
                else -> null
            }
        }.minOrNull()

/** [block]'s value, or null once it failed, said in [log] as [what] with no more than the failure's kind and reason. */
internal suspend fun <T> attempt(
    log: (String) -> Unit,
    what: String,
    block: suspend () -> T,
): T? = failure(log, what, block).getOrNull()

/** [block]'s result, its failure said in [log] as [what]; only cancellation goes on. */
internal suspend fun <T> failure(
    log: (String) -> Unit,
    what: String,
    block: suspend () -> T,
): Result<T> =
    runCatching { block() }.onFailure { e ->
        if (e is CancellationException) throw e
        log("$what (${e::class.simpleName}: ${e.message})")
    }

/** How soon a pass runs again while a deposit is posted and not mined. */
internal val FOLLOW_EVERY = 10.seconds

/** How soon a pass runs again while an address holds enough but couldn't be signed for: the terms didn't read, say. */
internal val SIGN_RETRY = 60.seconds

/** A transaction the relayer named that hasn't mined within this is posted again, as the same bytes. */
internal const val REPOST_SECONDS = 60L

/** How long an address refused twice, or whose deposit reverted, waits before it's signed for again. */
private const val PAUSE_SECONDS = 60 * 60L
