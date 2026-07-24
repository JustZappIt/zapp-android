package co.electriccoin.zcash.ui.screen.migration.keystonesign

/**
 * Device-safety cap on how many PCZTs one Keystone batch-signing QR round trip may cover.
 * Set to 40 to match the wallet-team action target settled on in Slack (`#ext-zodl-valargroup`,
 * 2026-07-24): 128 actions / 40 transactions max, after Harry reliably signed a 40-transfer batch
 * (40×3 = 120 actions) end-to-end on device against the fixed Keystone firmware. This also aligns
 * with the shipped Vizor reference implementation (`valargroup/vizor-wallet` PR #73,
 * `keystone.rs:124`), which caps at 40 — see
 * `docs/superpowers/specs/2026-07-19-vizor-migration-reference-comparison.md` §2.4. (An earlier,
 * more conservative 35 predated the 2026-07-24 on-device validation.) A migration whose split +
 * schedule together exceed this is split into multiple rounds (see
 * [keystoneBatchTotalRounds]/[keystoneBatchRoundSlice]) rather than sent as one oversized batch.
 */
const val KEYSTONE_BATCH_MAX_ITEMS = 40

/**
 * One round of a (possibly multi-round) Keystone batch-signing sequence: whether this round's QR
 * includes the note-split PCZT (always round 0, if a split exists at all), and which slice of the
 * schedule's transfer list (by index into the full, unchunked list) belongs to this round.
 */
data class KeystoneBatchRoundSlice(
    val includeSplit: Boolean,
    val transferRange: IntRange,
)

/**
 * How many QR-signing rounds a batch of [transferCount] schedule transfers (plus an optional note
 * split) needs, given a device-safe cap of [maxItems] PCZTs per round. The split (if present)
 * always occupies one slot of round 0; every other slot in every round is a transfer.
 */
fun keystoneBatchTotalRounds(
    hasSplit: Boolean,
    transferCount: Int,
    maxItems: Int,
): Int {
    val totalItems = transferCount + if (hasSplit) 1 else 0
    if (totalItems == 0) return 0
    return (totalItems + maxItems - 1) / maxItems
}

/**
 * The [KeystoneBatchRoundSlice] for [roundIndex] (0-based) of a batch covering [transferCount]
 * schedule transfers plus an optional note split, chunked at [maxItems] PCZTs per round. Rounds
 * are filled in order — split first (round 0 only), then transfers packed tightly into each
 * round's remaining budget.
 */
fun keystoneBatchRoundSlice(
    roundIndex: Int,
    hasSplit: Boolean,
    transferCount: Int,
    maxItems: Int,
): KeystoneBatchRoundSlice {
    require(roundIndex >= 0) { "roundIndex must be non-negative, was $roundIndex" }

    var consumed = 0
    for (i in 0 until roundIndex) {
        val budget = if (i == 0 && hasSplit) maxItems - 1 else maxItems
        consumed += budget.coerceAtLeast(0)
    }

    val includeSplit = hasSplit && roundIndex == 0
    val budgetThisRound = (if (includeSplit) maxItems - 1 else maxItems).coerceAtLeast(0)
    val start = consumed.coerceAtMost(transferCount)
    val end = (start + budgetThisRound).coerceAtMost(transferCount)
    return KeystoneBatchRoundSlice(includeSplit, start until end)
}
