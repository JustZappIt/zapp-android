# Pokrytie „something was missed" reschedulingu enginom — evaluácia (28.7.2026)

Kontext: Slack C0A82K4NX52 ts 1785231345.187899 — dominik žiada core team o „migration plan
shift" metódu (AN background postponed / iOS notifikácia nekliknutá). Danny odkazuje na
`rebuild_expired_transfer` (engine.rs ~L1738/1774 public, ~L1823 inner/unsigned) s poznámkou
na L1772.

Zdroje overené priamo v kóde:
- librustzcash **main** @ `77cd50cde1` (28.7.): `zcash_pool_migration/src/{engine,state,scheduling}.rs`
- publikovaný **rc.1**: `~/.cargo/registry/src/…/zcash_pool_migration-0.1.0-rc.1/src/`
- náš JNI: `zcash-android-wallet-sdk/backend-lib/src/main/rust/migration.rs`
- POZOR: SDK aktuálne builduje proti **main cez path-patch** (`backend-lib/Cargo.toml:167-182`,
  vrátane #2801 `sync_wakeup_schedule`); pred release treba prepnúť späť na publikované craty.

---

## Kľúčové overené dizajnové fakty

1. **ZIP 374 sign-now/prove-later potvrdené v kóde**: sighash pokrýva `expiry_height`, anchor
   NIE — „the signature hash covers the expiry height" (engine.rs:1747), „Anchors and
   witnesses stay DEFERRED (ZIP 374): the fresh anchor is installed at proving time"
   (engine.rs:1753). Prove krok inštaluje anchor+witness cez PCZT Updater proti
   PERZISTOVANÉMU `anchor_boundary` (engine.rs:1525-1583 `prove_transfer`).
2. **Boundary checkpointy sa NEPRÚNUJÚ po ~100 blokoch.** Obava „retention 100 < bucket 144"
   je vyriešená durable anchor retention (issue #2700): `zcash_client_backend
   data_api/anchor_retention.rs` (`AnchorRetention::union`, `retains()`) +
   `zcash_client_sqlite/src/lib.rs:2161-2188` — od NU6.3 aktivácie sa checkpointy na
   grid-boundary výškach (union: nakonfigurovaný interval + intervaly VŠETKÝCH committed
   migrácií, čítané z DB) držia ako durable, mimo bežného `PRUNING_DEPTH=100`
   (zcash_client_sqlite/src/lib.rs:171). Je to aj v publikovaných rc crates
   (zcash_client_backend 0.24.0-rc.2, zcash_client_sqlite 0.22.0-rc.2). Náš `open_at` to
   aktivuje: `.with_anchor_retention_interval(...)` (migration.rs:114-139).
3. **Nič v engine nemení `scheduled_height` iba-zmeškaného (neexpirovaného) transferu.**
   Jediná mutácia `scheduled_height` v engine je v `rebuild_expired_transfer_inner`
   (engine.rs:2008) — a tá vyžaduje expiráciu (guard `NotExpired`, engine.rs:1870-1872).
   „Shift" pre zmeškané-ale-živé transfery je **by design zbytočný**: `next_broadcastable`
   servíruje Proved transfer s `scheduled_height <= target` bez ohľadu na to, o koľko je
   okno preč (state.rs:271-284), a expirované explicitne filtruje.

---

## S1 — worker posunutý o minúty–hodiny; Signed + UŽ proved

**PLNE POKRYTÉ enginom.** `MigrationState::next_step` (state.rs:421) vráti
`AdvanceStep::Broadcast` cez `next_broadcastable` (state.rs:271) — podmienka je len
`Proved && scheduled_height <= target && deps_mined && !expired`. Zmeškané okno nie je stav,
je to len „due dávno". Expiry je kanonická (~40k blokov v našich plánoch; `expiry_height()` =
1–2× `EXPIRY_MODULUS`, scheduling.rs:644-656), takže hodiny–dni meškania nič nekazia.

**App vrstva**: náš `nextDueTransferNative` (migration.rs:2017) to už robí (tri-state,
expiry vždy proti scanned tip). Privacy caveat: broadcast hneď po sync burste koreluje
sync↔tx — rieši náš privacy buffer v Lane B (two-lane spec §1), nie engine.

## S2 — zmeškané okno; proof EŠTE NIE JE (kritická otázka)

**PLNE POKRYTÉ — prekvapivo bez rebuildu.** Reťaz:

1. Boundary checkpoint starého committed boundary **nie je pruned** (durable retention,
   fakt 2 vyššie). Wallet, ktorý dobehne sync cez boundary výšky, si grid checkpointy
   vytvorí/drží pri `put_blocks` (zcash_client_sqlite/src/lib.rs:2161-2196).
2. `next_step` vráti `AdvanceStep::Prove` len čo je boundary settled (`boundary + 1 <
   target`, state.rs:237-254 `prove_ready`); prove nemá deadline okrem expiry —
   `prove_transfer` (engine.rs:1541) zlyhá len na `AnchorIntervalMismatch` (zmena gridu,
   engine.rs:1461) alebo missing checkpoint (`WalletProveError::AnchorNotFound/
   WitnessNotFound`, wallet.rs:414-419 — pri durable retention nastane len po zmene gridu
   alebo korupcii).
3. Signed+neexpirovaný transfer sa teda dá dokázať aj o dni neskôr → potom S1.

**Rebuild NIE JE cesta pre tento prípad** — `RebuildError::NotExpired` (engine.rs:1645-1648)
ho odmietne. **Re-anchor-bez-resignu engine ako pomenované primitívum NEEXPONUJE** (žiadna
verejná metóda nemení `anchor_boundary` mimo rebuildu). ALE: verejné konštruktory
`MigrationTransaction::from_parts`/`MigrationState::from_parts` (engine.rs:338/524, aj v
rc.1: 326/512) to umožňujú — a náš JNI to už implementuje:
`rescheduleUnprovenTransferNative` (migration.rs:2493-2582 + `select_shift_boundary`
:2397, `reschedule_unproven_transfer_inner`:2429) — posunie `scheduled_height`, voliteľne
predraw-ne boundary na settled grid-multiple, `expiry_height` a PCZT bajty nemenné (ZIP 374
korektné). Obmedzenie: len `Signed` (Proved → -1). Caveat: náš redraw je uniformný, nie
recency-weighted Geometric(1/2) ako `draw_anchor_boundary` (scheduling.rs:815-885) —
zdokumentovaná odchýlka (migration.rs:2549-2551), kozmeticky slabšia anonymita kohorty.

## S3 — transfer EXPIROVANÝ (chain prešiel expiry_height)

**PLNE POKRYTÉ enginom, NEZAPOJENÉ u nás.** Sémantika potvrdená (engine.rs:1738-1786 +
1789-1817 + inner 1823-2013):

- úplne NOVÁ tx; funding note podľa **nullifier identity** z jedného real spendu starej PCZT
  (engine.rs:1905-1953) — nikdy nie iná nota rovnakej hodnoty (double-spend súrodenca);
- fresh memoryless delay od tipu → nový `scheduled_height`, kanonický nový `expiry`,
  fresh boundary (engine.rs:1955-1966); denominácia nemenná; **re-sign povinný** (sighash
  kryje expiry);
- in-process: `rebuild_expired_transfer` (public, main:1774 / rc.1:1580); Keystone:
  `rebuild_expired_transfer_unsigned` (main:1803 / rc.1:1609) → `AwaitingSignature` →
  `apply_signature` (state.rs:404);
- `RebuildError` (engine.rs:1636): `FundingNoteUnavailable` → re-plan zvyšku;
  `AnchorIntervalMismatch` (grid-change, engine.rs:1667-1676) → re-plan celej migrácie;
  `NotATransfer` — expirovaná PREPARATION nemá single-tx rebuild (dependents' podpisy
  commitujú na jej noty) → nová signing ceremony (state.rs:112-121 `Blocker::Expired`);
- detekcia: `next_step` → `AdvanceStep::Rebuild` (state.rs:440), `expired_transactions`
  (state.rs:203).

**Náš gap**: JNI `rebuild_expired_transfer*` vôbec neexportuje. `recordTransferResultNative`
tag 3 (migration.rs:1157-1200) označí CELÚ migráciu `Failed` + invalidation reason
`transfer_expired` → user musí re-plánovať všetko. Engine pritom vie opraviť jediný
transfer. Pri ~40k-blokovej expiry je to zriedkavé (mesiac mŕtvy device), ale je to
vedomé ochudobnenie — hodné F1 ticketu.

## S4 — veľa transferov zmeškaných naraz (deň testnet / týždeň mainnet)

**ČIASTOČNÉ — korektnosť engine, pacing app.** Engine servíruje po jednom
(`next_step`/`next_broadcastable` = prvý due), ale nič mu nebráni byť volaný v slučke →
back-to-back broadcasty všetkých zmeškaných = korelačný cluster (presne to, čo ZIP 318
kumulatívne delaye maskujú). Engine zmeškané **nerozostupuje** — `scheduled_height` ostáva
v minulosti. `sync_wakeup_schedule` (state.rs:308, len main, PR #2801) zmeškané proofy
zbalí do jedného immediate wake-upu „right now" (scheduling.rs:677-684) — rieši batch
PROVING, nie rozostup BROADCASTOV.

**App vrstva (naša, hotová)**: at-most-one-overdue v `CheckMigrationRecoveryUseCase` —
najskorší overdue sa nechá, zvyšok sa shiftne `rescheduleUnprovenTransfer` (two-lane spec
§1); + privacy buffer medzi broadcastmi. Diera: shift funguje len na `Signed`; viac
**Proved** overdue naraz vie rozostúpiť len časovanie Lane B (nič perzistentné).
`hasOverdueTransfersNative`/`any_overdue` (migration.rs:1718-1738) drží sync gate.

## S5 — iOS notifikácia nekliknutá, app otvorená oveľa neskôr

**Nič navyše oproti S1–S4.** Notifikácie nie sú load-bearing; app-open beží
`expired_transactions` + `next_step` reťaz (state.rs:195-209 to explicitne opisuje ako
„detection a wallet runs on launch"). Ten istý engine, tá istá odpoveď: neexpirované →
prove+broadcast hneď (S1/S2), expirované → rebuild (S3), viacero → pacing na app vrstve
(S4). iOS pozor: swift vrstva má Transfer-only filter v broadcast slučke (deadlock bug,
u nás opravený — two-lane spec invariant 4); a `rebuild_expired_transfer_unsigned` je pre
Keystone-štýl externé podpisovanie relevantný aj tam.

## Dannyho L1772 poznámka — interpretácia

L1772 (lokálne presne sedí s GitHub main): *„The caller persists the updated state
afterwards (`replace_migration`), exactly as after proving."* — čiže rebuild mutuje len
in-memory `MigrationState`; perzistencia je na volajúcom. Dannyho „Yes, IIUC, that's what
it does" je ale **len čiastočne trefné** na dominikovu požiadavku „not change notes or even
times ideally — just shift the plan": rebuild (a) vyžaduje EXPIRÁCIU, (b) žrebuje NOVÝ čas
aj expiry, (c) vyžaduje RE-SIGN. Skutočná odpoveď na „plan shift" je: **pre neexpirované
transfery shift netreba** (engine ich servíruje late) a metóda na to zámerne neexistuje;
jediné, čo app potrebuje navyše (privacy rozostup zmeškaných), sme si už postavili cez
`from_parts` (`rescheduleUnprovenTransferNative`) — ZIP-374-korektne, lebo sighash anchor
nekryje.

---

## Dostupnosť primitív: rc.1 vs main

| Primitívum | rc.1 (0.1.0-rc.1) | main @77cd50cde1 |
|---|---|---|
| `next_step`/`AdvanceStep` (vrát. `Rebuild`) | ✅ state.rs:372/35 | ✅ state.rs:421/37 |
| `next_broadcastable` (expiry guard) | ✅ state.rs:269 | ✅ state.rs:271 |
| `next_provable`/`prove_ready` | ✅ state.rs:258 | ✅ state.rs:260/237 |
| `expired_transactions`/`is_expired` | ✅ state.rs:201/185 | ✅ state.rs:203/187 |
| `rebuild_expired_transfer` | ✅ engine.rs:1580 | ✅ engine.rs:1774 |
| `rebuild_expired_transfer_unsigned` | ✅ engine.rs:1609 | ✅ engine.rs:1803 |
| `apply_signature`, `mark_broadcast/mined` | ✅ | ✅ |
| `MigrationState/Transaction::from_parts` | ✅ engine.rs:326/512 | ✅ engine.rs:338/524 |
| `sync_wakeup_schedule` + `WakeupParams`/`SyncWakeup`/`schedule_sync_wakeups` | ❌ CHÝBA | ✅ state.rs:308, scheduling.rs:364/432/692 (#2801) |
| durable anchor retention (client_backend/sqlite) | ✅ (0.24.0-rc.2 / 0.22.0-rc.2) | ✅ |

(rc.1 používa `MigrationTxId`, main premenované na `MigrationTransferId` — len rename.)

Náš JNI (migration.rs) exportuje: `nextDueTransferNative` (tri-state, :2017),
`finalizeReadyTransfersNative` (prove, :1877), `recordTransferResultNative` (:1113),
`rescheduleUnprovenTransferNative` (:2493), `hasOverdueTransfersNative` (:1741).
**NEexportuje**: `rebuild_expired_transfer*`, `sync_wakeup_schedule`.

## Verdikt

Engine pokrýva všetko okrem dvoch vecí, ktoré sú vedome ponechané app vrstve: (1) privacy
rozostup viacerých zmeškaných broadcastov (S4 — máme), (2) rozhodnutie kedy rebuild vs
re-plan pri expirácii (S3 — nemáme zapojené, dnes degradujeme na Failed). Nová „plan shift"
metóda od core teamu NIE JE potrebná.

## Na doriešenie s Dannym/Krisom

1. **Release publish `sync_wakeup_schedule`**: #2801 je len na maine; potrebujeme
   zcash_pool_migration rc.2 (alebo git pin) pred release buildom — teraz sedíme na
   path-patchi.
2. **Rebuild pre expirované namiesto plan-death**: potvrdiť, že preferovaný flow pri
   `transfer_expired` je `AdvanceStep::Rebuild` → `rebuild_expired_transfer(_unsigned)`
   per-transfer, a nie náš dnešný „mark whole migration Failed"; ak áno, chceme JNI export
   (Keystone: unsigned + `apply_signature`).
3. **Posvätiť náš `from_parts` shift**: `rescheduleUnprovenTransferNative` robí
   re-anchor+shift bez re-signu (Signed only, expiry nemenné). Je to podľa core teamu OK
   použitie verejného API, alebo chcú z toho spraviť engine primitívum (ideálne s
   recency-weighted redrawom namiesto nášho uniformného)?
4. **Proved overdue cluster**: viac Proved transferov naraz due — má engine niekedy dostať
   „re-space" (nemožné bez re-signu meniť expiry, ale scheduled_height by šlo), alebo je
   app-side pacing (privacy buffer) finálna odpoveď?
5. **iOS**: nahlásiť Transfer-only filter v swift broadcast slučke (deadlock ekvivalent
   nášho 2026-07-28 nálezu).
