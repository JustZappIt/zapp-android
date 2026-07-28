# Core-team point — duplicate `broadcast_height` in `proposeMigrationTransfers`

Draft for #orchard-ironwood-migration (Kris / Danny). Engine: `zcash_pool_migration` @ a00f4a7a.

---

**Issue: `proposeMigrationTransfers` returns multiple transfers with an identical `broadcast_height`.**

**What we observe (testnet, live):** a 12-transfer plan (3 prep layers) came back with three
transfers scheduled at the exact same broadcast height:

```
tip = 4214251
transfer[3]  broadcast_height = 4214308
transfer[9]  broadcast_height = 4214308
transfer[11] broadcast_height = 4214308
transfer[6]  broadcast_height = 4214319
transfer[4]  broadcast_height = 4214325
... (the rest are distinct)
```

**Root cause we traced:** `schedule_broadcast_heights` → `cumulative_broadcast_heights` advances a
running height by an independently drawn `transfer_delay()` per transfer
(`height = height + draw()`), and `DelayDistribution::draw_inner` is documented to return a value
"always in `[0, cap]`" — i.e. **0 is a legal draw**. So the cumulative sequence is only
*non-decreasing*, not strictly increasing, and consecutive zero-draws collapse to equal heights.
On testnet the transfer-delay mean is one bucket interval = 12 blocks, so P(draw rounds to 0)
≈ 4% per transfer — collisions are common; on mainnet (mean 144) it is ~0.35%, so rare but still
possible.

**Why we think this is worth raising:** ZIP 318 requires migration transactions to be broadcast
one at a time (temporal decorrelation), and the whole point of the randomized per-transfer delay is
to spread them out. Assigning several transfers the *same* `broadcast_height` seems to work against
that intent — and a consumer that treated `broadcast_height` as the actual send block (rather than
an "earliest eligible" floor) would broadcast them in the same block, violating the one-at-a-time
rule.

**How it manifests / our current handling:** our Android background sender broadcasts strictly one
transfer per worker run with a privacy buffer between broadcasts (`next_broadcastable` serves one at
a time, and we impose our own quiet-gap), so we never actually broadcast simultaneously — equal
scheduled heights are absorbed by our serialization. So this is not breaking us today; we want to
confirm the intended contract.

**Question:** Is a non-decreasing (equal-allowed) `broadcast_height` intended — i.e.
`broadcast_height` is a "not before" floor and the wallet is expected to serialize — or should
`schedule_broadcast_heights` enforce strictly-increasing heights / a minimum inter-transfer spacing
so the schedule itself never places two transfers in the same block?

---

**Secondary (minor, not a bug):** anchor boundaries are drawn at `commit_preparation`, so a
*proposed* (pre-commit) plan carries no per-transfer anchor. That's understood and fine for
execution; it only means the pre-commit review screen can't display real anchor heights (we show
them post-commit from the persisted `anchor_boundary`). If exposing the drawn boundaries at propose
time were cheap it would let the review screen show the real anchors — but not important.
