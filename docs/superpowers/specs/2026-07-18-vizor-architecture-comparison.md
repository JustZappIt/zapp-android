# Vizor (chainapsis/vizor-wallet) architecture, reverse-engineered, vs. our migration design

## Context

`chainapsis/vizor-wallet` is a Flutter Zcash wallet with a Rust core (via `flutter_rust_bridge`),
run by the same `valargroup` contributors behind the Keystone firmware/`keystone-sdk-rust`
batch-signing PRs found earlier today. This document reverse-engineers Vizor's relevant architecture
from its public GitHub repo and compares it against our own Orchard→Ironwood migration design
(`librustzcash/docs/superpowers/specs/2026-07-17-migration-sign-now-prove-later-design.md`,
`zashi-android/docs/superpowers/specs/2026-07-16-migration-manual-scheduling-unification-design.md`).

## Vizor's architecture, as reverse-engineered

### No Orchard→Ironwood migration equivalent exists

Exhaustive `gh search code` across `chainapsis/vizor-wallet` for `ironwood`, `turnstile`,
`pool_migration`/`poolmigration` returned **zero results**. `orchard`/`denomination` hits are all
generic (ordinary Orchard sends, sync, ZEC-amount formatting) — none are pool-crossing-specific.
Vizor's own `AGENTS.md` describes only an ordinary hardware-wallet **send** flow (single
recipient, single PCZT) and explicitly notes "No-op Sapling provers for Orchard-only software TXs" —
Vizor's wallet operates in the Orchard pool for ordinary sends today, with no dedicated migration
feature, staggered-transfer schedule, or note-splitting concept anywhere in the codebase. This is a
clean negative finding, not a gap in the search — there is nothing to compare our schedule/
sign-now-prove-later design against on Vizor's side.

### Sync engine — same underlying crates, different block-caching strategy

Vizor's `rust/src/wallet/sync_engine/` (`mod.rs`, `block_source.rs`, `lwd.rs`, `enhance.rs`,
`mempool.rs`) is built on the identical `zcash_client_backend`/`zcash_client_sqlite` primitives our
`CompactBlockProcessor` uses (`scan_cached_blocks`, `WalletCommitmentTrees`, `ConfirmationsPolicy`,
`ScanRange`/`ScanPriority`) — same core scanning architecture, not a divergent reimplementation.

The one genuine structural difference: Vizor's `MemoryBlockSource`
(`rust/src/wallet/sync_engine/block_source.rs:1-20`) keeps each batch of downloaded compact blocks
**in memory only** (bounded ≤300 blocks desktop / ≤100 mobile) and feeds `scan_cached_blocks`
directly from it, explicitly to avoid an on-disk block-cache file format entirely ("one less thing
to clear on reorg/rewind", per that file's own doc comment). Our `CompactBlockProcessor` instead
persists downloaded blocks to an on-disk `FsBlockDb`-style cache
(`zcash_sdk_testnet_fs_cache/blocks`, observed in this session's own device logs) before scanning
and deleting them. Vizor's approach trades a small amount of peak memory for one less on-disk schema
to keep migration-compatible across app versions — a real, deliberate simplification, though not
directly applicable to our migration feature (it's a general sync-engine choice, unrelated to
pool-crossing).

### Keystone/PCZT/UR handling — closely matches what we built today, independently

Vizor's `rust/src/wallet/keystone.rs` (full file, 279 lines) and its FRB-exposed surface
`rust/src/api/keystone.rs`:

- **Encode:** `encode_pczt_ur_parts(pczt_bytes, max_fragment_len) -> Vec<String>`
  (`keystone.rs:243-268`) — wraps one PCZT in `ZcashPczt`, CBOR-encodes, drives `ur::Encoder` to
  **eagerly generate every UR part up front** (loop over `encoder.fragment_count()`, calling
  `next_part()` each time), returning the full `Vec<String>` rather than a stateful "pull one frame
  at a time" encoder. **This is the exact same shape** our new `migration_keystone.rs::build_sign_batch_qr_parts`
  uses (eager `Vec<String>`, not a retained stateful encoder) — arrived at independently, not copied,
  since Vizor's batch UR types weren't used as a reference for this (they don't use them — see next
  section) — but it's a good sign the two implementations converged on the same "just generate all
  frames now" simplicity rather than a more complex stateful-ticker design.
- **Decode:** `decode_ur_part(part, expected_ur_type) -> UrDecodeResult` (`keystone.rs:127-238`),
  backed by a single `static Mutex<Option<UrSession>>` (`keystone.rs:104-107`) — auto-resets on type
  mismatch or completion, single-part UR short-circuits immediately, multi-part accumulates via
  `ur::Decoder::receive`/`.progress()`/`.complete()`/`.message()`. Our
  `migration_keystone.rs::decode_sign_batch_part` mirrors this almost exactly (same `static
  Mutex<Option<ur::Decoder>>` session shape, same reset-on-type-mismatch/reset-on-completion
  semantics) — this was **built by directly reading and imitating this exact file** during today's
  implementation (per the plan at `.claude-z/plans/lucky-sprouting-squirrel.md`), so the close match
  here is expected, not a coincidence.
- **`reset_ur_session`/`resetKeystoneSignBatchDecoder`:** both call sites document the same
  concern — the scan screen must guarantee a clean decoder state *before* the first scan callback
  fires, not race a fire-and-forget reset against the camera. Vizor's Rust side marks its reset
  `#[frb(sync)]` specifically for this reason (`api/keystone.rs:33-38`); our Kotlin side achieves the
  same ordering guarantee by having the ViewModel call `resetKeystoneSignBatchDecoder()` synchronously
  in its scan-screen-entry `init`/`viewModelScope.launch` before wiring up the camera callback (no
  `frb`-style sync annotation available/needed on our JNI boundary, but the same intent).

**Vizor does not use the batch UR types at all.** Their `rust/Cargo.toml` pins `pczt = "0.7"`
(predates `pczt::roles::signer::batch`, only added in a newer `pczt` release) and their
`ur-registry`/`ur` git pins, while from the same upstream repos we use, don't exercise
`ZcashSignBatch`/`ZcashBatchSigResult` anywhere in their own code — only single-PCZT `ZcashPczt`.
There is no batch-signing reference implementation to compare against; the batch-specific half of
today's work (`BatchSignRequest`/`BatchSignResponse` construction, request-id correlation) was
necessarily new work, verified only by our own Rust unit/e2e tests, not by mirroring a working
example.

## Comparison with our migration design

**Where they align:** the core "signatures don't require a real witness, only proofs do" insight
that makes our sign-now/prove-later design possible has no direct Vizor counterpart to compare
against (no migration feature), but Vizor's ordinary Keystone send flow independently confirms the
same underlying PCZT role separation our design leans on: their own `AGENTS.md` documents the exact
role sequence **Creator → IoFinalizer (phone) → Prover (phone) → Redactor (phone) → Signer
(device) → Combiner + TransactionExtractor (phone)** — Redactor runs on the phone *before* the
device ever sees the PCZT, and Prover runs *before* Redactor, meaning Vizor's ordinary send flow
already proves before redacting/signing, never after. Our migration design's novelty is deferring
proving until *after* signing (and until a real witness exists) specifically for the placeholder-
witness self-funding transfers — a scenario Vizor's ordinary send flow has no reason to encounter
(a normal send always spends an already-witnessed note), so this isn't something Vizor validates or
contradicts, just an extension our feature specifically needed and Vizor's use case doesn't.

**Where they genuinely differ:** Vizor has no note-splitting, no staggered-transfer schedule, no
pool-crossing concept, and (as of today) no batch-signing usage at all — the entire migration domain
this session's work covers has no Vizor analog to diverge from or align with.

**Worth stealing:** the eager "generate every UR part up front into a `Vec<String>`" encode
pattern (already independently adopted) and the `#[frb(sync)]`-motivated "reset must complete before
the first scan callback" framing are both small, concrete validations that today's
`migration_keystone.rs` design choices match an independently-arrived-at, shipped pattern from a
team with direct Keystone integration experience — not a new idea to adopt, but a useful sanity check
that we didn't over- or under-engineer the UR session handling.

**Worth noting for completeness (nothing to steal, just documented difference):** Vizor's in-memory
`MemoryBlockSource` sync-engine choice is unrelated to migration but is a real, deliberate
architectural divergence from our on-disk `FsBlockDb`-style cache — out of scope for this
comparison's purpose, noted only because it surfaced during the investigation.
