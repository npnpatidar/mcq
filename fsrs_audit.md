# FSRS-7 audit — `feature/fsrs-7`

- **Branch:** `feature/fsrs-7`, audited at `f255c56` (PR #82, draft)
- **Base:** `origin/main` (`7f47596`) — 16 commits, 59 files, +7201/−1505
- **Date:** 2026-11-15
- **CI evidence:** run `43736218580` on `f255c56` green (`wrapper-validation`,
  `release-build`, `build`, `ui-test`); `:app:testDebugUnitTest` = 266 classes,
  2202 tests, 0 failures, 7 skipped (none FSRS)
- **Method:** three specialist reviewer subagents, run strictly sequentially and
  read-only, then a supervisor pass that re-checked the two contested claims
  against upstream source. No file under the repo was modified by any reviewer.
- **Gate result:** 0 blockers, **2 majors**, 15 minors, 8 nits

## Verification standard

Every algorithmic claim below was checked against upstream source, not against
this repo's own documents. `fsrs7-upgrade-plan.md`, `blueprint.md`, `audit.md`,
`threshold*.md`, `calibration.md`, `ladder-evaluation.md` and the `ChatGPT_*`
drafts were written here, may themselves be wrong, and code agreeing with them
proves nothing. Where upstream and a local document disagree, the disagreement
is recorded (§8) rather than resolved in the local document's favour.

The platform half was checked against the official Android/Room, SQLite,
DataStore and Anki documentation, with URLs in §10.

## Upstream basis

**"FSRS-7" is a real upstream model**, not a local invention:

- `open-spaced-repetition/fsrs-rs` tag `v7.0.0` — `src/model_v7.rs`
  (`PARAM_LEN = 34`), `parameter_initialization_fsrs7.rs`,
  `parameter_clipper_v7.rs`, `training_v7.rs`
- `open-spaced-repetition/ts-fsrs` — `packages/fsrs/src/models/fsrs-7/`
  (`algorithm.ts`, `constants.ts`, `algorithm.spec.ts`)
- Anki's own `Cargo.toml` still pins **fsrs 6.6.2** (21 parameters), i.e. Anki
  has not yet migrated to the 34-weight model, so `fsrs-rs v7.0.0` — not Anki —
  is the authoritative reference for the engine.

## 1. Verdict

The engine is a faithful and numerically sound implementation of FSRS-7. All
four 34-entry constant arrays are value-for-value identical to `fsrs-rs`
`v7.0.0`; the model equations reproduce an independent port of `model_v7.rs` to
≤ 4.9992e-9 (exactly `ts-fsrs`' 8-decimal rounding); the interval solver agrees
to 1.5e-14 relative error over 2400 randomised cases; and the parity vector in
`FsrsSchedulerTest` is quoted verbatim from `ts-fsrs`' published spec, so it is
genuine upstream parity rather than a self-authored pin.

The defects are at the edges, not in the model: one real format-compatibility
break on the export path, one untested production path that the relearn cap
depends on, and a documentation layer with claims that upstream contradicts or
that no longer describe the code.

## 2. Majors

### M1 — a new export silently restores zero review progress on an older build

| | |
|---|---|
| Where | `data/io/Exporter.kt:40,171,236`; key built by `data/io/CardScheduleMapping.kt:29`; readers `data/io/Importer.kt:589-593`, `data/io/StreamingImportSession.kt:637` |
| Severity | major |

Both export paths now key the `scheduling` map `"$paperId\u0000$questionId"`,
while `origin/main`'s importer looks up the bare `questionDto.id` only
(`git show origin/main:…/Importer.kt:586`). The exporter still writes the
literal `{"version":1,…}` (`Exporter.kt:40`, `JsonDto.kt:182` defaults to `1`),
and **no importer reads `version` at all** — the field is decorative, so a
format change cannot be detected or migrated.

Consequence: a bank exported by this branch and restored into any older install
matches nothing and restores **no** scheduling rows. It is silent, because
`ui/library/LibraryScreen.kt:666` only reports a count when it is greater than
zero. The composite key itself was a correct fix (two papers sharing a question
id), but it was bought at the cost of every older reader.

Test blindness: every round-trip test in the suite is export→import of the
**same** build, no test asserts the written version, and no committed fixture
exists for a v1 file with bare-id keys. The suite would pass if the key format
changed again tomorrow.

Smallest correct fix: keep writing the composite key, and additionally emit the
bare `questionId` key **when that id is unique within the file** (the composite
key stays authoritative where the same id appears under two papers). Bump
`version` to 2, have the importer honour it, and add a committed v1 fixture plus
a version assertion on a real export.

### M2 — the production exposure count has no test

| | |
|---|---|
| Where | `data/repository/StudyStore.kt:516-527` (`exposureInStudyDay`), `data/local/Daos.kt:2116` (`countForCardSince`) |
| Severity | major (coverage) |

`exposureInStudyDay` = `countForCardSince(paperId, questionId, dayStart) + 1` is
the only production source of the three-exposure relearn cap. Nothing tests it:
`FsrsSchedulerTest:550,567` pass the count in explicitly, and `FsrsReplayTest`
reconstructs the count independently. A wrong `since` boundary, or a dropped
`paperId` in the `WHERE`, would give every card an unlimited relearn budget — the
exact behaviour the scheduling rules forbid — and the suite would stay green.

Smallest correct fix: one Robolectric test that inserts a review row before
`DayBoundary.startOfDay`, one after it, and one for the same question id under a
different paper, then asserts `exposureInStudyDay(...) == 2`.

## 3. Minors

| # | Where | Issue |
|---|---|---|
| m1 | `data/anki/AnkiScheduling.kt:147` | The SM-2 bridge passes `desiredRetention`; upstream `memory_state_from_sm2_scalar(w, interval, sm2_retention)` takes a separate retention, which Anki feeds from `historical_retention` (deck option, default 0.9). When the user's desired retention is not 0.9 the inferred `(S, Sf)` differs from what Anki would infer. The bridge stays self-consistent (the next interval lands back on the imported one), so this is wrong *state*, not a wrong visible schedule. |
| m2 | `data/repository/FsrsTuner.kt:80-86` | `storeIfImproved` accepts "both losses finite and lower" only. A degenerate fit (`items == 0`, `steps == 0`) can report a trivially better loss and overwrite a real user fit with the shipped defaults; upstream's `params.rs` returns the current parameters unchanged when there are no `fsrs_items`. |
| m3 | `data/repository/RepositorySettings.kt:373` vs `:441` | A successful refit never invalidates the FSRS-7 rebuild: `setFsrsParameters` does not clear the done flag, and `fillMissingFastStability` only fills rows with `stabilityFast = 0`. Stored `(S, Sf, D)` are a blend of two weight sets until each card is next reviewed; retrievability and the due ranking use the new weights on old state. Self-healing per card, but wrong on every card at once. |
| m4 | `data/repository/Fsrs7Upgrade.kt:148` vs `data/local/Daos.kt:2029` | Two orderings for one log: the rebuild sorts one card's rows by `(reviewedAt, id)`, the paged replay that feeds statistics consumes append (`id`) order. Diverges only when the clock steps backwards. The log is append-only with monotonic ids, so `id` alone is the correct total order. |
| m5 | `data/repository/RepositorySettings.kt:347-350` via `setSchedulerConfig:324-341` | `writeWeights` unconditionally stamps `fsrs_model_version = 7`. A settings write in the window before the async `ensureFsrs7` (`McqApplication.onCreate`) short-circuits the `hadFit` branch, so a learner who had a genuine 21-weight fit never sees the one-shot explanation. Scheduling outcome is identical; the *information* is lost. |
| m6 | `data/repository/McqRepository.kt:405-415` called from `ui/settings/SettingsViewModel.kt:697` | `settingsRestore` runs the FSRS-7 rebuild inline: a large backup's "restoring…" phase is dominated by the rebuild, and because `SettingsViewModel` catches around the whole `restoreStreaming` call, a rebuild failure surfaces as "Restore failed" although the wipe/replace and the settings write already committed. |
| m7 | `data/anki/AnkiPackageReader.kt:589,439` | The `cards` SELECT omits `data`, where Anki stores real FSRS memory state (`s`, `d`, `dr`, `decay`, `lrt`; `rslib/src/storage/card/data.rs`; schema 11 guarantees the column). Every FSRS-aware Anki import is re-derived from a rounded day count plus `D = 5`, discarding state Anki already knew. |
| m8 | `ui/stats/StatsViewModel.kt:125` → `McqRepository.kt:356` → `StatsStore.kt:591` → `Daos.kt:2069` | The drill-down loads history by `questionId` alone while every other surface is keyed `(paperId, questionId)`. A question shared by two papers mixes two independently scheduled cards: wrong stability line, wrong attempt list, a sawtooth trend that never happened. Needs `historyForCard(paperId, questionId)`. |
| m9 | `data/repository/StudyStore.kt:273-290` | `bestDueReview` materialises the entire due backlog and ranks it in Kotlin, per card served. `StudyPullQueryCountTest` pins query *count*, not rows, so an unbounded scan is invisible. Push the ranking into SQL or `LIMIT` a candidate window before the Kotlin tie-break. |
| m10 | `app/src/test/java/com/mcqapp/FsrsOptimizerTest.kt:253,281,474` | The objective sum, the 34-entry gradient and the fitted-parameter goldens are **self-generated**; the finite-difference test at `:315` validates the gradient against the implementation's own loss. Add one `fsrs-rs training.rs` fit vector, or state "self-generated, no upstream anchor" in the header. |
| m11 | `domain/FsrsOptimizer.kt` seq cap; `FsrsOptimizerTest.kt:425` | Upstream `training.rs` *drops* histories longer than `max_seq_len` (`retain(|i| i.reviews.len() <= config.max_seq_len)`); the app truncates by position, and the test names that truncation "the reference's cap". Adopt `retain`, or rename the test and cite upstream. |
| m12 | `data/io/Backup.kt:1142` | The restore path's `"partial"` outcome is untested — only success and hard failure are. A restore that silently drops half the rows reports success. Add a truncated-zip-member test. |
| m13 | `app/src/test/java/com/mcqapp/Fsrs7UpgradeTest.kt:76` | `setUp` calls `ensureFsrs7()` before touching the store, an ordering production does not guarantee (it runs in an `onCreate` coroutine), so the first-launch race is untestable as written. Drive upgrade and a concurrent read from two coroutines, or document the assumption. |
| m14 | `AGENTS.md:162-165` | The `Sf` invariant is too narrow — see §8.1. |
| m15 | `README.md:1290-1300,1563` | Documents `scheduling` keyed by bare `"q-001"` and `version: Int = 1`, the format the exporter no longer writes. `ReadmeExamplesTest` does not cover the snippet, so docs and code disagree unnoticed. |

## 4. Nits

| # | Where | Issue |
|---|---|---|
| n1 | `domain/Fsrs.kt:537` (`recallDerivative`) | Divides by `curve.total` (`fastWeight + slowWeight`) with no floor; upstream floors with `.max(1e-9)` for both recall and derivative. Unreachable with clipped weights, but `trainingScheduler` (`Fsrs.kt:674`) is deliberately unclipped, where a near-zero sum blows up the Newton step. Mirror upstream's floor. |
| n2 | `domain/FsrsOptimizer.kt:126,133,135` | `EPOCHS = 9` and `MAX_SEQ_LEN = 1024` are `fsrs-rs` **library** defaults; Anki's Optimize passes `num_epochs: 8` and leaves `max_seq_len` at 256. The KDoc framing the loop as the counterpart of Anki's Optimize button is overstated. |
| n3 | `data/io/StreamingImportSession.kt:637` vs `data/io/Importer.kt:589` | Same lookup, opposite precedence (bare-first vs composite-first). Funnel both through one `lookupSchedule(map, paperId, questionId)`. |
| n4 | `AGENTS.md:172-179` | Says the third exposure yields `RELEARNING` with positive `intervalDays`; `FsrsSchedulerTest:550` shows a **new** card's third exposure yields `LEARNING`. Qualify it. |
| n5 | `app/src/test/java/com/mcqapp/RepositorySettingsTest.kt:1-40` | Header claims the class runs on the ARM64 host; it is Robolectric-marked and cannot (aarch64 SQLite). Misleads anyone choosing a local subset. |
| n6 | `.github/workflows/release.yml` | The release job runs only `assembleRelease` — no `:app:testDebugUnitTest` — so a tag can ship an untested commit. |
| n7 | `FsrsReplayTest.kt` (`sameDayReviewsAdvanceThroughShortTermStability`) | Name still says "short-term stability", an FSRS-6 concept; FSRS-7 has no short-term branch, it has two traces. |
| n8 | `README.md` FSRS-7 section | The displayed `R(t,S)` omits the clamps, the `f_f`/`f_s` mixture and the `e^((D-5)(w32-0.3))` timescale, so a reader cannot reproduce the numbers from it. Add "simplified" plus a pointer to `FsrsScheduler.forgettingCurve`. |

## 5. Verified correct — model

- **Constants**, all byte/value-identical to `fsrs-rs v7.0.0`:
  `DEFAULT_FSRS_PARAMETERS` (34 — *not* FSRS-6's 21 padded),
  `LOWER_BOUNDS`/`UPPER_BOUNDS` (including the cross-constraints),
  `PARAMS_STDDEV` (including the four `9999.0` sentinels), and the optimiser
  hyperparameters `LEARNING_RATE 0.0118`, `BATCH_SIZE 512`,
  `RANDOM_SEED 2023`, `ADAM_BETA_1 0.70`, `ADAM_BETA_2 0.98`,
  `ADAM_EPSILON 1e-8`, `L2_WEIGHT 0.3333`, `MIN_ITEMS_FOR_TRAINING 64`.
- **Equations:** `recall` (difficulty-modulated mixture with the `1e-5` /
  `1 − 2e-5` affine correction) and the update step reproduce `model_v7.rs` to
  ≤ 4.9992e-9 over 24 state/rating cases.
- **Parity:** `FsrsSchedulerTest.kt:45-80` is a verbatim copy of `ts-fsrs`
  `algorithm.spec.ts:77-82`; `next_states` reproduces
  `0.5974598` / `4.7777247` / `53.869392` to published precision.
- **Solver:** `intervalDaysFor` = numerical solve then
  `round().coerceIn(1L, maxIntervalDays)`, matching Anki's 1-day minimum;
  agreement with upstream 1.5e-14 over 2400 randomised cases; `stateForInterval`
  uses upstream's bisection over `ln(stability)` with the endpoint early-returns.
- **Optimiser:** unnormalised weighted BCE with the recency weight
  `0.0667 + 0.9333·(i/n)^11.25`, L2 toward the defaults scaled `0.3333·batch/total`,
  Adam with bias correction, cosine annealing, clipping after every step, and a
  loss-only persistence guard (a NaN loss is never stored).

## 6. Verified correct — persistence, backup, UI

- **Migration 26→27** is two `ALTER TABLE … ADD COLUMN` statements, legal under
  SQLite's ADD COLUMN restrictions; no table rebuild, no destructive fallback.
  Generated `AppDatabase_Impl.kt:126,131` emits
  `stabilityFast REAL NOT NULL DEFAULT 0.0` and `stabilityFastAfter REAL`, and
  the expected `TableInfo.Column(defaultValue = "0.0")` matches `27.json` and the
  migration string exactly. Room's post-migration validation compared column
  maps including defaults.
- **Sentinel choice is right:** `0.0` decodes as "unknown" and collapses onto
  `stability`, so every pre-v27 row reads correctly without a backfill;
  `stabilityFastAfter` stays nullable so old log rows carry "no fast trace"
  rather than a fake zero.
- **Rebuild** is a single `UPDATE … WHERE lastReviewedAt = :expected`, so a
  concurrent review wins or loses whole; it writes only `(S, Sf, D)` and leaves
  `dueAt`, `intervalDays`, `reps`, `lapses`, `leech`, `lastReviewedAt`,
  `contentHash` untouched.
- **Paging** `pageCardKeysAfterRowId` uses
  `GROUP BY (paperId, questionId) HAVING MIN(id) > :cursor`, so each card appears
  exactly once regardless of where its rows fall; `countCards()` agrees.
- **Backup zip** is genuinely forward- and backward-compatible: versioned
  manifest, `ignoreUnknownKeys`, defaulted new fields, and `fsrs_fit_version`
  round-trips (a retired fit resets to 0). This is the contrast that makes M1
  the odd one out — the zip path already solved the same problem correctly.
- **All writers carry the fast trace:** five `CardStateEntity` sites and both
  `ReviewLogEntity` sites.
- **UI truthfulness:** stability is never rendered with a `d` suffix, the
  histogram buckets treat it as model time, no copy claims "21 parameters", and
  the upgrade notice is exactly one-shot and silent on a fresh install.

## 7. Scheduling invariants × coverage

| Invariant | Covering test | Status |
|---|---|---|
| `stabilityFast == 0` = unknown, collapse onto `stability` | `FsrsSchedulerTest:372`, `MigrationTest` (0.0 default), `Fsrs7UpgradeTest:97` | covered |
| 0.8× only for first exposure / `Again` / SM-2 bridge | `AnkiSchedulingTest:60`, `FsrsSchedulerTest` first-exposure cases | covered, doc needs fixing (m14) |
| Stability is not an interval | `FsrsSchedulerTest:237`, `:250` | covered |
| No short-term branch; elapsed time fractional | `FsrsSchedulerTest:286`, `:343` | covered |
| Three-exposure budget per study day | `FsrsSchedulerTest:550`, `:567`; `FsrsReplayTest` — but **not** `StudyStore.exposureInStudyDay` | partly — see M2 |
| 21→34 cannot convert → defaults + `fsrs_fit_version = 0` + one-shot notice | `RepositorySettingsTest:118-150,153,176`; `Fsrs7UpgradeTest:578` | covered |
| Rebuild writes only `(S, Sf, D)` under CAS | `Fsrs7UpgradeTest:97,399,487` | covered |
| 34 defaults / parity vector upstream-pinned | `FsrsSchedulerTest:34-110` | covered |
| Export format version honest | — | **uncovered** — see M1 |

## 8. Adjudicated contradictions

### 8.1 The `Sf` invariant — reviewer 1 correct, reviewer 3 wrong

Reviewer 1 held that `Fsrs.kt:310-314` writing `Sf = 0.8·S` on Anki import
contradicts `AGENTS.md`'s "never write a fabricated `Sf`". Reviewer 3 held the
opposite, that the sentence is correct and the import path merely pins it.

The source settles it (`fsrs-rs v7.0.0`, `src/model_v7.rs:12-29`):

```rust
pub(crate) fn memory_state_from_sm2_scalar(
    w: &[f32], interval: f32, sm2_retention: f32,
) -> Result<MemoryState> {
    let state_at = |log_stability: f32| {
        let stability = log_stability.exp().clamp(S_MIN, S_MAX);
        MemoryState {
            stability,
            difficulty: 5.0,
            stability_fast: (stability * 0.8).clamp(S_MIN, S_MAX),
        }
    };
```

Upstream fabricates `Sf = 0.8·S` and fixes `D = 5` for an **already-learned**
imported card. The code is right; the invariant is written too broadly, and a
future agent following it literally would "fix" correct code into a divergence.
Proposed wording: "…legitimate only for a first exposure, an `Again`, or the
SM-2 import bridge, which mirrors upstream's `memory_state_from_sm2_scalar`."

### 8.2 Local plan vs upstream

The four places the local plan (`fsrs7-upgrade-plan.md`, recovered from `435fa8e`)
diverges from upstream — and the code tracks **upstream** in all four:

| Area | Plan said | Upstream does | Code |
|---|---|---|---|
| Anki SM-2 import | fabricate `Sf = S`, derive `D` by inverting ease | `Sf = 0.8·S`, `D = 5`, ease ignored | follows upstream — correct |
| Initial-stability fit range | clamp `S0` to 100 | evaluates to 36500; 100 is only the fit ceiling | follows upstream — correct |
| Optimiser batching order | review-time order | sort by (prefix length, card id) | follows upstream — correct |
| Training-item guard | keep if any card has ≥ 2 long-term reviews | keep unless every item has exactly one | follows upstream — correct |

No local document's error has propagated into the engine. The `Sf` sentence is
the one place a local document still instructs otherwise.

## 9. Owner decisions outstanding

1. **m1** — pass Anki's `historical_retention` (0.9 default) to the bridge, or
   record the deliberate simplification in code and `AGENTS.md`.
2. **m7** — read `cards.data` to keep a real FSRS state on import, or accept the
   SM-2 re-derivation as a documented limitation.
3. **m11** — adopt upstream's `retain`-not-truncate sequence cap, or keep
   truncation and rename the test.
4. **m14** — approve the widened `Sf` invariant wording.
5. **M1** — confirm that emitting a bare key alongside the composite key is
   acceptable, rather than only bumping `version`.

## 10. Reference basis

Upstream source read at the pinned tag:

- `fsrs-rs v7.0.0` — `model_v7.rs` (`PARAM_LEN = 34`, 0.8 at `:27`, Again cap
  `:207`, mean reversion, curve clamps), `parameter_initialization_fsrs7.rs`,
  `parameter_clipper_v7.rs`, `training.rs` (`num_epochs 9`, `lr 0.0118`,
  batch 512, `max_seq_len 1024`, `retain(...)` at `:873`, recency `:625`,
  `MIN_ITEMS_TO_TRAIN 64` `:44`), `training_v7.rs`, `dataset.rs`,
  `cosine_annealing.rs`, `inference*.rs`
- `ts-fsrs` — `packages/fsrs/src/models/fsrs-7/{algorithm,constants,parameters,model}.ts`,
  `algorithm.spec.ts`; `docs/…/fsrs-7.mdx`
- `ankitects/anki` — `rslib/src/scheduler/fsrs/{params,memory_state,rescheduler}.rs`
  (`num_epochs: 8` at `params.rs:146`; keep-unless-strictly-better `:152-158`),
  `rslib/src/storage/card/data.rs`, `rslib/src/storage/schema11.sql`,
  `proto/anki/deck_config.proto` (`historical_retention`, default 0.9),
  root `Cargo.toml` (fsrs 6.6.2 pin)
- Anki manual — deck options / FSRS

Platform documentation:

- SQLite `ALTER TABLE` restrictions — <https://www.sqlite.org/lang_altertable.html>
- Room migrations and schema validation —
  <https://developer.android.com/training/data-storage/room/migrating-db-versions>
- Room column defaults —
  <https://github.com/androidx-releases/Room/releases/tag/2.2.0-alpha01>
- DataStore transactional `edit` —
  <https://developer.android.com/topic/libraries/architecture/datastore>
- Gradle `--tests` filter semantics —
  <https://docs.gradle.org/current/userguide/java_testing.html>

Marked unverified against upstream:

- that `TableInfo.equals` compares `defaultValue` — rests on the Room release
  note above plus the generated `TableInfo` in this repo; the androidx
  `room-runtime` source paths were unreachable (404).
- Anki's `cards.data` **key names** (`s`, `d`, `dr`, `decay`, `lrt`) come from
  the `CardData` serde renames, not from `schema11.sql`, which only guarantees
  `data text NOT NULL`.
- The app's own Optimize fidelity (m10, m11) was checked against `training.rs`
  but not numerically reproduced.

## 11. How this audit was run

- Three reviewer subagents, strictly sequential, read-only, each with its own
  scope: design/domain, implementation/integration, tests/docs.
- The domain reviewer was re-run after the owner ruled that local design
  documents are not evidence; it then re-derived the model from a from-scratch
  port of `model_v7.rs` (scratch files under `/tmp/opencode`, never the repo)
  and diffed it against the Kotlin.
- The supervisor independently re-checked the export/version defect
  (`Exporter.kt:40`, `Importer.kt:586,592`) and the `Sf` contradiction
  (`model_v7.rs:12-29`) rather than forwarding reviewer claims unverified.
- Local execution: 92 pure-JVM FSRS tests green on this aarch64 host; the
  Robolectric/SQLite classes cannot run here and were judged from CI run
  `43736218580` instead.
- No production code was modified during the audit. The only working-tree
  changes were the owner's `AGENTS.md` supervisor-rule additions.

## 12. Suggested order of work

1. **M1** — versioned export plus a v1 fixture (the only defect that loses user
   data on a real, if old, install).
2. **M2** — the exposure-count test (cheapest guard against the worst silent
   regression in the scheduler).
3. **m2, m6, m8, m9** — the four defects with a concrete learner-visible
   consequence: a fit that can be lost to a no-op, a restore misreported as
   failed, mixed drill-down history, and an unbounded per-pull scan.
4. **m3, m4, m5** — state consistency after a refit, one log ordering, and the
   swallowed upgrade notice.
5. **Docs, one commit** — m14 (widened `Sf`), m15 (README export example),
   n2 (Optimize claim), n4 (third-exposure wording), n8 (formula caveat),
   plus the upstream anchor on the FSRS-7 section.
6. **Test hardening** — m10, m12, m13, n5.
7. **m1, m7, m11** — owner decisions, then either implementation or a recorded
   rationale in `AGENTS.md`.
