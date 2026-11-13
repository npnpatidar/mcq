# Upgrading the in-house FSRS-6 implementation to FSRS-7

Status: **plan only — no code changed.**
Target: the shipped 34-weight FSRS-7 (fsrs-rs 7.0.0 `model_v7.rs` / ts-fsrs v6
`models/fsrs-7`), not the older 35-parameter srs-benchmark layout.

This document is written against the code as it exists on `main` today:

| Area | File |
| --- | --- |
| Engine | `app/src/main/java/com/mcqapp/domain/Fsrs.kt` |
| Optimizer | `app/src/main/java/com/mcqapp/domain/FsrsOptimizer.kt` |
| Domain types / config | `app/src/main/java/com/mcqapp/domain/SpacedRepetition.kt` |
| Replay | `app/src/main/java/com/mcqapp/domain/stats/FsrsReplay.kt` |
| Training-card builder | `app/src/main/java/com/mcqapp/data/repository/FsrsTuner.kt` |
| Persistence | `data/local/Entities.kt`, `data/local/Daos.kt`, `data/local/AppDatabase.kt` |
| Settings | `data/repository/RepositorySettings.kt` |
| Anki mapping | `data/anki/AnkiScheduling.kt` |
| Backup / JSON | `data/backup/Backup.kt`, `data/io/JsonDto.kt` |
| UI | `ui/settings/StudySchedulerSection.kt`, `ui/stats/*` |

---

## 1. Executive summary

FSRS-7 is **not** an incremental tune of FSRS-6. Three things break at once:

1. **Parameterization.** 21 weights → 34 weights. The weights are not a superset:
   FSRS-6's `w20` (the decay) disappears and the moments/decay family is replaced
   by a two-curve mixture (`w23`–`w33`). `w7` changes meaning from "difficulty
   mean-reversion" to "slow-trace growth scale". **No FSRS-6 weight set can be
   converted.** A fitted set must be discarded and re-optimized.
2. **State.** One stability → two (`stability` slow, `stabilityFast` fast), plus
   difficulty. Every stored memory state must be rebuilt by replaying history.
   FSRS-7's own migration guide is explicit: *do not* invent
   `stabilityFast = 0.8 × stability` for an already-learned card — that factor is
   only valid at initialization and after an `Again`.
3. **Time semantics.** FSRS-7 has **no same-day/short-term branch**. Every review
   (including `t = 0`) runs the same two update formulas at fractional elapsed
   time. And stability is **no longer "days until recall probability reaches 90%"**
   — the interval must be solved numerically from the mixed curve. That invalidates
   the two places that currently assume `S == interval`.

Work is therefore four independent pieces, each of which can land and be verified
on its own:

- **A. Engine** (`Fsrs.kt`) — new formulas, `stabilityFast`, numerical interval.
- **B. Schema** — `card_state.stabilityFast`, `review_log.stabilityFastAfter`,
  DB v27, backup/JSON plumbing.
- **C. Migration** — 21 → 34 weights (settings) and FSRS-6 → FSRS-7 memory state
  (replay rebuild, `dueAt` untouched).
- **D. Optimizer** — new data rules, new hyperparameters, new bounds/L2, post-fit
  monotone repair.

---

## 2. What FSRS-7 is, precisely

Notation: `S` = `stability`, `Sf` = `stabilityFast`, `D` = difficulty,
`t` = fractional elapsed days, `G` ∈ 1..4 = Again/Hard/Good/Easy.

### 2.1 Initial memory

```
S0(G)  = clamp(w[G-1], 1e-4, 100)
Sf0    = clamp(0.8 * S0, 1e-4, 36500)
D0(G)  = w[4] - e^(w[5]*(G-1)) + 1
D_init = clamp(D0(G), 1, 10)
```

`Sf0 = 0.8·S0` is legitimate **here only** (new card), and after `Again`.

### 2.2 The dual-trace forgetting curve

```
t   = max(t, 0)
df  = -clamp(w23 * Sf^(w33 - 0.3), 0.01, 0.95)
Ff  = exp(min(ln(w25)/df, 60)) - 1
Rf  = (1 + Ff * t / Sf)^df

ds  = -clamp(w24, 0.01, 0.95)
Fs  = (w26^(1/ds) - 1) * e^((D-5)(w32 - 0.3))
Rs  = (1 + Fs * t / S)^ds

af  = w27 * Sf^(-w29)
as_ = w28 * S^w30 * e^((D-5)(w31 - 0.5))
R   = (af*Rf + as_*Rs) / (af + as_)
R   = R * (1 - 2e-5) + 1e-5          // so R(0) = 0.99999
```

Consequences worth stating in code comments:

- `Rf(Sf) = w25` and `Rs(S) = w26·e^(ds(D-5)(w32-0.3))`; neither is 0.9, and the
  mix is not 0.9 either. **`S` is not an interval.**
- The mixture weights depend on the *state*, not on `t`, so `prepare_curve()` can
  be hoisted out of any loop that evaluates several `t` for one state — this is the
  single biggest optimizer cost saving (see §9.4).

### 2.3 Update after a review

For a trace stability `x`, recall input `q`, and offset `k`:

```
fail(x,q,k) = w[k+3] * ((x+1)^w[k+4] - 1) * e^((1-q)*w[k+5])
L           = min(x, fail(x,q,k))

H = w[k+6] if G == 2 else 1          // Hard penalty
E = w[k+7] if G == 4 else 1          // Easy bonus

U = x * (1 + e^(w[k]-1.5) * (11-D) * x^(-w[k+1])
             * (e^((1-q)*w[k+2]) - 1) * H * E)

T = L                    if G == 1
  = max(L, U)            otherwise
```

- slow trace: `k = 7`, `q = R` (the mixed recall of §2.2)
- fast trace: `k = 15`, `q = Rf` (**raw fast recall**, never the mix)

Then:

```
S'  = clamp(T7(S, R, G),  1e-4, 36500)
Sf' = clamp(T15(Sf, Rf, G), 1e-4, 36500)
if G == 1: Sf' = min(Sf', 0.8 * S')
```

Both traces are updated on **every** review, same-day included. There is no
`t < 1` branch and no `w17..w19` short-term formula.

### 2.4 Difficulty

Uses **pre-review** `D`:

```
dD = -w6 * (G - 3)
if G == 1: dD *= (R + 0.1)

D' = clamp(0.01*D0(4) + 0.99*(D + (10-D)*dD/9), 1, 10)
```

The mean-reversion coefficient is **fixed at 0.01/0.99**; FSRS-6's `w7` does not
appear here any more.

### 2.5 State rounding

The reference rounds the three new state values to 8 decimals before clamping
(`roundTo(..., 8)`). This exists so f32 arithmetic stays reproducible. We compute
in f64; mirroring the rounding keeps our state at the same numeric grain as the
published vectors and makes the parity test exact rather than tolerant. Do it.

### 2.6 Interval from stability

There is no closed form. `next_interval` returns **fractional days**:

```
target = clamp(desiredRetention, 1e-4, 0.9999)
if target >= 0.9999: return 0
logT = ln(max(S, Sf))
repeat 7 times:
    logT = clamp(logT, ln(1/86400), ln(36500))
    t    = clamp(e^logT, 1/86400, 36500)
    {R, dR/dt} = curve(t)
    logT -= clamp((R - target) / min(dR/dt * t, -1e-12), -4, +4)
I = clamp(e^logT, 0, 36500)
if |curve(I).R - target| <= 1e-3: return I
// bisection fallback, 50 iterations, high starts at max(max(S,Sf), 1) and doubles
```

### 2.7 Reference parity vector

Defaults, ratings `[Again, Good, Good, Good, Good, Good]`, elapsed days
`[0, 0, 1, 3, 8, 21]`, state `(D, S, Sf)` **after** each review:

| # | D | S | Sf |
| --- | --- | --- | --- |
| 1 | 6.1686 | 0.1104 | 0.0883 |
| 2 | 6.1092 | 0.1104 | 0.0883 |
| 3 | 6.0504 | 0.9169 | 44.4442 |
| 4 | 5.9922 | 3.5338 | 193.5973 |
| 5 | 5.9346 | 10.1860 | 407.6265 |
| 6 | 5.8775 | 25.9857 | 700.5589 |

(Computed from the ts-fsrs reference suite. Row 1 is `S0(Again)=w0=0.1104`,
`Sf0=0.8·w0=0.08832`, `D0(1)=w4-1+1=6.1686` — a quick self-check that the port has
the right index layout.)

### 2.8 Default weights

```
0.1104, 2.2395, 3.9221, 11.7841, 6.1686, 0.6457, 3.6807, 1.9795,
0, 1.3826, 0.7024, 0.5999, 0.8146, 0.6398, 1, 1.3207,
0.6707, 3.8668, 0.4416, 0.0934, 1.8631, 0.6162, 1.0869, 0.1567,
0.0801, 0.2421, 0.9464, 0.1433, 0.7145, 0, 0.5667, 0.3734,
0.5333, 0.3048
```

### 2.9 Bounds (fsrs-rs `parameter_clipper_v7.rs`, mirrored by ts-fsrs)

| idx | box | note |
| --- | --- | --- |
| 0 | 1e-4 .. 50 | S0(Again); half the init ceiling |
| 1,2,3 | 1e-4 .. 100 | monotone: `w1≥w0`, `w2≥w1`, `w3≥w2` |
| 4 | 1 .. 10 | D0 anchor |
| 5 | 0.001 .. 4 | D0 slope |
| 6 | 0.1 .. 4 | difficulty step |
| 7..14 | 0..4, 0..1.2, 0.3..3, 0.01..1.5, 0.1..1, 0..3.5, 0..1, 1..7 | slow block (`sinc` scale, `sinc` exponent, `sinc` q-scale, `fail` scale, `fail` exponent, `fail` q-scale, Hard, Easy) |
| 15..22 | 0..4, 0..2, 0.5..6, 0.001..1.5, 0.001..1, 0..5, 0..1, 1..7 | fast block, same order |
| 23 | 0.01 .. 0.25 | fast decay |
| 24 | 0.01 .. 0.95 | slow decay |
| 25 | 0.2 .. 0.85 | fast anchor retention |
| 26 | 0.5 .. 0.99 | slow anchor retention, **and `w26 ≥ max(w25, 0.5)`** |
| 27 | 0.01 .. 1 | fast mixture weight |
| 28 | 0.1 .. 1 | slow mixture weight |
| 29 | 0 .. 0.9 | fast weight exponent |
| 30 | 0.1 .. 1.1 | slow weight exponent |
| 31 | 0 .. 1 | mixture difficulty offset |
| 32 | 0 .. 0.6 | slow curve difficulty coupling |
| 33 | 0 .. 0.6 | fast decay exponent |

---

## 3. What this repo does today, and what has to change

| Concern | FSRS-6 today | FSRS-7 |
| --- | --- | --- |
| `PARAMETER_COUNT` | 21 | 34 |
| Decay | one `w20`, closed form | two clamped decays `w23`,`w24` |
| Recall curve | one power law | weighted mixture of two |
| Same-day review | separate branch (`w17..w19`) | no branch — same formulas at `t≈0` |
| `S` meaning | days to 90% | model quantity; interval must be solved |
| `intervalFor()` | closed form + round | Newton + bisection + round |
| `w7` | difficulty mean reversion | slow-trace growth scale |
| Optimizer targets | inter-day only (`Δt ≥ 1`) | every review after the first |
| Optimizer start point | closed-form S0 fit + outlier filter | official defaults, no prep |
| L2 | 0.5, 21 σ | 0.3333, 34 σ (first 4 = 9999) |

---

## 4. Phase A — the engine (`domain/Fsrs.kt`)

### 4.1 Public surface

```kotlin
class FsrsScheduler(
    parameters: List<Double>? = null,
    val desiredRetention: Double = DEFAULT_DESIRED_RETENTION,
    private val config: SchedulerConfig = SchedulerConfig(),
    private val zoneOffset: (Long) -> Long = DayBoundary::zoneOffset
) : Scheduler
```

Unchanged signature. `PARAMETER_COUNT = 34`. `DEFAULT_FSRS_PARAMETERS` becomes the
34-value list of §2.8. `STABILITY_MIN` becomes `1e-4` (the model bound; today it is
`1e-3`, which silently clips optimiser output).

### 4.2 Change list inside the file

1. **Delete** `decay`/`factor` fields, `shortTermStability()`, `retrievabilityOf()`,
   `forgetStability()`, `recallStability()`, `initialStability()` in their current
   forms.
2. **Add** a small value class for the curve:

   ```kotlin
   private class Curve(
       val fastDecay: Double, val fastRatio: Double,      // df, Ff/Sf
       val slowDecay: Double, val slowRatio: Double,      // ds, Fs/S
       val fastWeight: Double, val slowWeight: Double, val total: Double
   )

   private fun prepareCurve(s: Double, sf: Double, d: Double): Curve
   private fun recall(curve: Curve, t: Double): Double
   private fun recallDerivative(curve: Curve, t: Double): Double
   ```

   `prepareCurve` clamps `s`, `sf` to `[1e-4, 36500]` and `d` to `[1, 10]` first —
   the reference does this on every entry point, and it is what keeps a corrupt or
   hand-edited row from producing `NaN`.

3. **Normalize the fast trace defensively.** FSRS-7 requires both stabilities > 0,
   and the reference throws otherwise. This app cannot throw: imported cards
   (`AnkiScheduling` writes `stability = intervalDays`, no fast trace), old rows
   from a partially completed rebuild, and hand-edited backups all arrive with
   `stabilityFast == 0.0`. Rule:

   ```kotlin
   // unknown fast trace collapses onto the slow one: the mixture then blends two
   // decays over one scale instead of claiming a within-day component we never saw.
   private fun fastOf(state: CardState) =
       if (state.stabilityFast > 0.0) state.stabilityFast else state.stability
   ```

   Document this at the property and in the class KDoc. It is exactly the rule that
   makes the §6 fallback path and imported cards safe; the rebuild (§6) exists to
   make it rare, not to make it unnecessary.

4. **`next()`** becomes:

   ```kotlin
   val hadMemory = state.state != NEW && state.stability > 0.0
   val t = elapsedDaysSinceLastReview(state, now)   // fractional, unchanged
   val (s, sf, d) = if (!hadMemory) initialMemory(rating)
                    else updateMemory(state.stability, fastOf(state),
                                      state.difficulty, t, rating)
   ```

   `updateMemory` implements §2.2–§2.5 verbatim, including the 8-decimal rounding
   and the `Again` fast-cap. **No `t < 1` branch.** Keep `elapsedDaysSinceLastReview`
   exactly as it is, including the "no timestamp but has memory ⇒ elapsed = interval"
   sentinel — that comment is still correct and still needed for Anki imports.

5. **`retention()`** uses `recall(prepareCurve(...), t)`. Keep the
   `stability <= 0 → 0.0` guard.

6. **`intervalFor()`** becomes an instance method taking the whole state (all three
   fields matter):

   ```kotlin
   private fun intervalDaysFor(state: CardState): Int
   ```

   Newton-in-log-t (7 iterations, step clamp ±4, tolerance 1e-3) then the 50-step
   bisection fallback, then `Math.round(...).coerceIn(1L, maxIntervalDays)`. The
   existing Long-narrowing comment in `intervalFor` still applies and should be
   kept (the solver can legitimately return 36500, and a fitted set can still
   saturate it).

7. **New public helper** for display, diagnostics, and the Anki inverse map:

   ```kotlin
   /** Fractional days until the mixed recall curve reaches [retention]. */
   fun intervalForRetention(state: CardState, retention: Double): Double
   ```

   Not used by `next()` (which rounds and goes through `DayBoundary`), but needed
   by `AnkiScheduling` and by anything that wants to show "S in days". This is the
   replacement for "S is the interval at 90%".

8. **`easeFromDifficulty`** unchanged (still `1.3..3.0`, still D↔ease).

9. **KDoc** rewritten: drop "three-component", drop "days until recall probability
   falls to the benchmark retention", keep the two deliberate deviations (no
   learning-step ladder, no fuzz) and add a third — *no fractional scheduling*:
   FSRS-7 returns fractional days, this app stores whole days (`intervalDays: Int`)
   because `DayBoundary.dueAfter`, the Anki day mapping and every UI surface are
   whole-day. The **elapsed** side is fractional, which is what FSRS-7 actually
   needs.

### 4.3 Deliberate deviation: no learning-step ladder

Unchanged from FSRS-6. A failed card still returns after `config.relearnMs` and a
pass takes the whole-day interval. FSRS-7's fast trace partially compensates: a
same-day repeat now genuinely moves `Sf`, which the old `w17..w19` branch also did.
No behaviour change is required in `Study.selectNext`.

---

## 5. Phase B — schema and persistence

### 5.1 Domain type

`domain/SpacedRepetition.kt`, `CardState`:

```kotlin
/** Days-equivalent of the fast trace; 0 when unknown (imported or un-rebuilt). */
val stabilityFast: Double = 0.0,
```

placed immediately after `stability`, so the four-argument `copy()` calls that
pass named arguments are unaffected.

`SchedulerConfig`:

- `PARAMETER_COUNT` reference in the KDoc ("21 FSRS-6 weights") → 34.
- `sanitized()` keeps its shape: wrong length / non-finite ⇒ defaults, otherwise
  `FsrsOptimizer.clip`. **This is what makes the 21-value preference safe** — see
  §5.4.
- No new config field is needed. `desiredRetention`, `maxIntervalDays`,
  `relearnMs`, `dayStartHour` all keep their meaning.

### 5.2 Room entities

`data/local/Entities.kt`:

```kotlin
// CardStateEntity
/** Fast-trace stability (FSRS-7, v27). 0 when unknown. */
val stabilityFast: Double = 0.0,
```

```kotlin
// ReviewLogEntity — the seed the replay and the trainer need.
/** Fast-trace stability after this review (FSRS-7, v27); null for pre-v27 rows. */
val stabilityFastAfter: Double? = null,
```

### 5.3 `AppDatabase`: v26 → v27

```kotlin
version = 27
```

```kotlin
/**
 * FSRS-7 (v27): the second stability trace on both the card and the log.
 * Both columns default, so every pre-v27 row survives verbatim and reads as
 * "fast trace unknown"; Fsrs7Upgrade rebuilds them from the log afterwards.
 * Historical log rows are deliberately NOT backfilled — the reference's
 * migration guide forbids rewriting recorded memory state.
 */
val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `card_state` ADD COLUMN `stabilityFast` " +
                "REAL NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "ALTER TABLE `review_log` ADD COLUMN `stabilityFastAfter` REAL"
        )
    }
}
```

Register it after `MIGRATION_25_26` in `builder(...)`.

Room needs the DAO projections updated for the new column:

- `CardStateRow` (the `SELECT *, rowid` keyset projection at `Daos.kt:1557`) —
  check whether it is an explicit column list or `SELECT *`; if explicit, add
  `stabilityFast`.
- `CardStateDao.updateMemory(...)` — new targeted update used by the rebuild
  (§6.3), not a full-row upsert.

### 5.4 Settings: weights, fit version, model version

`RepositorySettings.kt` already degrades safely: a stored 21-value CSV fails the
`size == PARAMETER_COUNT` check in `schedulerConfig()` and falls back to defaults.
The problem is *provenance*: `fsrs_fit_version` would keep the old number, so the
stats screen would claim "fitted to your history" while running defaults.

Add:

```kotlin
/** The FSRS model version the stored weights belong to. 0 = pre-FSRS-7. */
private val fsrsModelVersionKey = intPreferencesKey("fsrs_model_version")
/** One-shot "your fitted FSRS-6 weights were retired" flag. */
private val fsrs7NoticeKey = booleanPreferencesKey("fsrs7_upgrade_notice")

/**
 * One-time model migration. Runs before the first scheduler read on an
 * upgraded install:
 *  - stores the FSRS-7 defaults (a 21-value set cannot be converted — the
 *    parameterization changed, not the count),
 *  - resets `fsrs_fit_version` to 0 so no review claims a fit under a model
 *    that was never fitted,
 *  - raises the one-shot notice only when the user actually had a fit to lose.
 */
suspend fun ensureFsrs7() { ... }
```

Called from `McqApplication.onCreate()` (fire-and-forget on an IO scope) and,
defensively, awaited by `FsrsTuner.optimize()` so a fit can never race it. The
notice is surfaced once in Settings → Study scheduler (§10).

Backup restore: `writeWeights(parameters)` sanitizes, so a backup carrying 21
weights restores as defaults. Bump `fsrs_model_version` there too, and set the
notice if the backup's set was non-default. *(Check `RepositorySettings` around
line 477 and 512 — `writeWeights(...)` calls in the restore path.)*

### 5.5 Backup and JSON

- `Backup.CardStateRow`: `val stabilityFast: Double = 0.0` (default keeps old
  backup files readable).
- `Backup.ReviewLogRow`: `val stabilityFastAfter: Double? = null`.
- Both `toEntity()` / `fromEntity()` pairs updated (`Backup.kt` ~579–594 and
  ~1358–1430).
- `JsonDto.CardScheduleDto`: `val stabilityFast: Double = 0.0`. This carries the
  trace through app→app JSON export and through the Anki importer path so it is not
  silently lost on a restore.
- `Importer` / `StreamingImportSession`: pass the field through; **do not** invent
  a value when it is 0 — §4.2 rule 3 handles it at read time.

### 5.6 Anki scheduling
`AnkiScheduling` currently assumes `stability = intervalDays` ("at the benchmark
retention a card's interval equals its stability"). That is false in FSRS-7.

- **Import (Anki → app):** derive difficulty from ease first (unchanged), then
  solve `S` such that the mixed curve with `Sf = S` and that `D` reaches
  `DEFAULT_DESIRED_RETENTION` at `t = intervalDays`, using the new
  `FsrsScheduler.intervalForRetention` inverse (a monotone 1-D solve; reuse the
  same Newton/bisection core with `S` as the unknown). This is still an estimate —
  an imported card's real traces are unknowable — but it no longer *undershoots*
  every interval, which a naive `S = interval` would now do.
- **Export (app → Anki):** `intervalDays` is already stored, so nothing changes.
- Add a short comment stating that this mapping is an inference, as the current
  one already does.

### 5.7 Replay and write plumbing

The third state field has to be carried by every path that materialises a
`CardState`, or the engine's `Sf = S` fallback quietly becomes the only behaviour:

| Site | Change |
| --- | --- |
| `StudyStore.CardStateEntity.toDomain()` (`StudyStore.kt:64`) | pass `stabilityFast = stabilityFast`. |
| The `CardState.copy(...)` in `FsrsScheduler.next()` | already covered by Phase A. |
| `StudyStore`'s log write | write `stabilityFastAfter = state.stabilityFast` next to the existing `stabilityAfter`. |
| `FsrsReplay.RawReview` + `ReplayReview` | new `stabilityFastAfter: Double?`; `seedState()` seeds `stabilityFast = stabilityFastAfter ?: stabilityAfter ?: 0.0`. |
| `FsrsReplay.Stream` | same, one extra field in its per-card `CardState`. |
| `HistoryStore` report evidence (`HistoryStore.kt:635`) | pass the projected value; if the projection does not select the column, add it. |
| `FsrsTuner.trainingCardOf()` | a seeded training card should carry `seedStabilityFast`; thread it into `PreparedCard.initialState()`. |

Every one of these has a default, so a missed site degrades accuracy rather than
crashing — which is exactly why they need to be checked off explicitly.

---

## 6. Phase C — data migration

### 6.1 Weight migration (21 → 34)

Handled entirely by §5.4. Key point to call out in the PR description: **an
FSRS-6 fit cannot be converted**, so the honest outcomes are "defaults" or
"re-optimize". Keeping 21 values next to a 34-slot engine would be worse than
either.

### 6.2 Card-state rebuild

Per the reference migration guide:

> For learned cards, select complete history … rebuild stability, stabilityFast and
> difficulty … without an old-model initialState. Apply the rebuilt memoryState
> only to the current card … do not change when existing cards are next due for
> review (dueAt).

So: **replay the log, write only `(stability, stabilityFast, difficulty)`, never
`dueAt`, `intervalDays`, `reps`, `lapses`, `leech`, `lastReviewedAt`, `contentHash`,
`ease`.** A card that is due today stays due today; only the memory numbers behind
its future intervals change.

New file `data/repository/Fsrs7Upgrade.kt`:

```kotlin
internal class Fsrs7Upgrade(db: AppDatabase, settings: SettingsStore) {
    suspend fun runIfNeeded(onProgress: (Int, Int) -> Unit = { _, _ -> })
}
```

Algorithm:

1. If `settings.fsrsModelVersion() >= 7`, return.
2. Read `settings.fsrsRebuildCursor()` (last `review_log.rowid` processed, 0 if
   absent). This makes the job **resumable**, which matters: a 1M-row replay must
   not lose its work to a process kill.
3. Keyset-page `review_log` by `rowid` in chunks (same idiom as
   `backfillQuestionTags` / `backfillFolded`), grouping rows by
   `(paperId, questionId)`.
4. For each card, replay with a **single `FsrsScheduler`** built from the current
   config, using **real fractional elapsed time from `reviewedAt`** (not rounded
   days). Seed rule, unchanged in shape from `FsrsReplay`:
   - first row `stateBefore != NEW && stabilityAfter != null && difficultyAfter != null`
     ⇒ seed from `stabilityAfter` / `stabilityFastAfter ?: stabilityAfter` /
     `difficultyAfter`, then replay the rest;
   - otherwise replay from a new card.
5. Collect `(paperId, questionId, lastReviewedAt, s, sf, d)` and flush every N cards
   (N ≈ 500) in one transaction.
6. After the last page: normalise cards that have **no** log rows —
   `UPDATE card_state SET stabilityFast = stability WHERE stability > 0 AND stabilityFast = 0`.
   (This is the only place a fallback is written, and it is the same "unknown fast
   trace" rule as §4.2; it is not the forbidden `0.8×` invention.)
7. Write `fsrs_rebuild_cursor = 0` and `fsrs_model_version = 7`.

### 6.3 Write safety

The rebuild and the study loop can interleave. Use a compare-and-set write:

```sql
UPDATE card_state
   SET stability = ?, stabilityFast = ?, difficulty = ?
 WHERE paperId = ? AND questionId = ? AND lastReviewedAt = ?
```

If a review landed in between, the predicate misses and **the live review wins** —
the right outcome, because that review was computed by the FSRS-7 engine (with the
`Sf = S` fallback) from a state at least as fresh as the rebuild's. Document this
in the class KDoc; it is the reason the rebuild does not need to hold a lock.

### 6.4 Where it runs

`McqApplication.onCreate()` → process-scoped `CoroutineScope(SupervisorJob() + Dispatchers.IO)`,
`yield()` between chunks, and log start/end + duration through `Logger`. No
WorkManager dependency is added (the project has none and the owner rule is "no new
test deps"; a new runtime dep deserves its own decision).

Ordering guarantee: because both the engine fallback (§4.2 rule 3) and the
CAS (§6.3) are correct without the rebuild, the app is never *broken* while it
runs, only slightly less accurate on a handful of cards.

### 6.5 Failure modes and what happens

| Situation | Behaviour |
| --- | --- |
| App killed mid-rebuild | resumes from `fsrs_rebuild_cursor` |
| Review graded mid-rebuild | CAS skips the stale write |
| Card has no log (pure Anki import) | `stabilityFast = stability` |
| Log has a seeded first row | seed from recorded post-state, `Sf` from `stabilityFastAfter` else `stabilityAfter` |
| Log row has a hand-edited `rating` outside 1..4 | folds to Good, as `FsrsReplay.gradeOf` already does |
| User already refit FSRS-7 before the rebuild finished | rebuild still only touches S/Sf/D; weights untouched |

---

## 7. Phase D — optimizer (`domain/FsrsOptimizer.kt`)

This is the largest single file change, and the one with the most fidelity risk.

### 7.1 Data rules (fsrs-rs `training.rs` / `training_v7.rs`)

| Rule | FSRS-6 (today) | FSRS-7 |
| --- | --- | --- |
| Target rows | prefixes ending at `Δt ≥ 1` day | **every review after the first** |
| `Δt` | rounded to whole days | **fractional**, clamped `≥ 0` |
| Outlier filter | yes (`computeRemovedPairs`) | **none** |
| S0 pre-fit | ternary search + `smoothAndFill` | **none** — train `w0..w3` like any weight |
| Start point | fitted S0 + defaults | **defaults** |
| Sequence cap | 256 | 1024 |
| Recency weight | `0.25 + 0.75·(i/(n-1))³` | `0.0667 + 0.9333·(i/n)^11.25` |
| LR | 0.04, 5 epochs | **0.0118, 9 epochs** |
| LR schedule | `(total/batch + 1)·epochs` | `batch_count · epochs` |
| L2 | 0.5, 21 σ | **0.3333, 34 σ** |
| Adam | 0.70 / 0.98 / 1e-8 | same |
| Batch | sorted by seq length, chunked | whole-card windows |
| Post-fit | `smoothAndFill(w0..w3)` | `smooth_initial_stabilities_fsrs7(w0..w3)` |
| Guard | `items < 64` or `items == initKept` | `items` empty, or every item has ≤ 1 long-term review |

### 7.2 Concrete edits

1. `PARAMETER_COUNT`-shaped arrays become 34 entries.
2. `PreparedCard`:
   - `deltas: IntArray` → `deltas: DoubleArray` (`Δt` in fractional days);
   - `ts: LongArray` (synthetic integer-day timeline) → `ts: DoubleArray` of
     fractional day offsets, starting at 0;
   - drop `firstInterday` (nothing filters on it any more);
   - `seeded` stays — the app's import story is unchanged.
3. Delete `computeRemovedPairs`, `searchInitialStabilities`, `initialStabilityLoss`,
   `smoothAndFill`, and the `InitSample` type. Replace `smoothAndFill` with:

   ```kotlin
   /** fsrs-rs `smooth_initial_stabilities_fsrs7`: clamp, then enforce non-decreasing. */
   internal fun smoothInitialStabilitiesFsrs7(s: DoubleArray): DoubleArray
   ```
   (The reference's log-anchor interpolation is only reachable when fewer than four
   ratings are "present", which cannot happen once all 34 weights are trained; keep
   it out and say so in a comment.)

4. `trainItems`: positions `1 until size` for complete cards, `0 until size` for
   seeded cards. `labels` = `rating > 1`.
5. `evaluateBatch`: build the scheduler once, and replace the per-item
   `retention(state, t)` calls with `prepareCurve` hoisted per *step position* —
   the curve depends only on the state, so one `prepareCurve` per replayed review
   serves both the loss evaluation and (later) any derivative use.
6. `gradient`: 34 weights × 2 evaluations = 68 batch replays per step. See §9.
7. Bounds / clipping: `LOWER_BOUNDS`/`UPPER_BOUNDS` per §2.9, plus the cross-parameter
   constraints that a box clip cannot express:
   - `w1 = max(w1, w0)`, `w2 = max(w2, w1)`, `w3 = max(w3, w2)`;
   - `w26 = max(w26, max(w25, 0.5))`.
   These must run **after** the box clamp, every step, in `clipInPlace`.
8. L2: `PARAMS_STDDEV` = the 34 values from `training_v7.rs`:
   `9999,9999,9999,9999,0.523,0.2528,0.4329,0.2966,0.2139,0.2889,0.1862,0.175,0.3812,0.3013,0.9104,0.3234,0.2448,0.3273,0.1842,0.1735,0.4608,0.311,0.864,0.0418,0.2596,0.0798,0.0682,0.1282,0.1397,0.1407,0.1489,0.2,0.15,0.15`,
   `L2_WEIGHT = 0.3333`.
9. `MIN_REVIEWS = 1000` stays (it is a UX gate, not a model constant).
10. `FsrsFitResult` doc comments: "21 weights" → 34, "inter-day review prefixes" →
    "review prefixes".

### 7.3 Batching

Replace `lossTerms.sortedBy { it.pos + 1 }.chunked(BATCH_SIZE)` with whole-card
windows: walk cards in time order (by their last item's timestamp), adding whole
cards until the window reaches `BATCH_SIZE` items, then close it. One card's
sequence is then never split across two gradients, which is what the reference's
windowed layout buys and what a 1024-cap sequence needs to be meaningful.

Exact batch composition changes fitted values slightly. That is acceptable: the
fitted set is not test-pinned, only the *engine* is (§11).

### 7.4 Prediction with a partial prefix

FSRS-7 has no "one long-term review" gate in the same shape as FSRS-6, so the
existing early-Return path becomes:

```kotlin
val enoughToTrain = trainItems.size >= MIN_ITEMS_FOR_TRAINING &&
    prepared.any { it.deltas.count { d -> d >= 1.0 } > 0 }
```

And with no first-exposure cards at all (a purely imported library), the FSRS-6
code kept the default `w0..w3`; FSRS-7 trains them from seeded histories too, so the
deviation disappears. Remove that block and its KDoc paragraph.

---

## 8. Scheduler policy: kept and changed

| Policy | Decision |
| --- | --- |
| Whole-day `intervalDays: Int` | **keep** — schema, `DayBoundary`, Anki export, and every screen assume whole days. |
| Fractional **elapsed** time | **keep** (already fractional) — now load-bearing, since FSRS-7 has no same-day branch. |
| No learning-step ladder | **keep** |
| No fuzz | **keep** |
| `Again` ⇒ `now + relearnMs` | **keep** |
| `desiredRetention` 0.75..0.97 | **keep** |
| `maxIntervalDays = 36500` | **keep**, matches `sMax` |
| `S == interval at 90%` | **remove** — solve numerically, everywhere |
| `ease` mirror | **keep** |

---

## 9. Performance

### 9.1 Where the cost lands

Today's fit is already the most expensive thing the app does: 21 weights × 2
finite-difference evaluations = 42 full batch replays per Adam step. FSRS-7 makes it
34 × 2 = 68, and each replay is heavier (two power laws, a mixture, six `pow`/`exp`
instead of four). Naively that is ~2.9× the current wall time (68/42 × 9/5).

### 9.2 It is fine for the realistic case

The fit is behind `MIN_REVIEWS = 1000` and is user-initiated with a progress bar and
cancellation. At 1k–10k reviews:

- 10k reviews ⇒ 20 batches ⇒ 180 steps ⇒ 12 240 batch replays of 512 items
  ⇒ ~6M item-replays. Each item is a handful of transcendentals, so this is
  seconds, not minutes, even single-threaded.

The gradient already fans out over `Dispatchers.Default` in chunks of 6 weights, so
the 34-weight version spreads over 6 coroutines with 12 weights each — keep the
chunk size proportional (`chunked(6)` still works; it just yields 6 chunks).

### 9.3 Where it would hurt

At 1M reviews the current recipe is already borderline; 2.9× is not acceptable.

### 9.4 Mitigations, in order of value

1. **Hoist `prepareCurve`.** It depends only on `(s, sf, d)`, which change once per
   replayed review, not per loss evaluation. Today `retention()` recomputes the
   decay and factor on every call. Doing this removes ~40% of the transcendental
   work outright and is required anyway for the Newton derivative.
2. **Skip interval solving during training.** `evaluateBatch` only needs `retention`
   and `next`; the new numerical `intervalDaysFor` must not be on that path. Split
   `next()` into `updateMemory()` (state only) + `intervalDaysFor()`, and have the
   trainer call `updateMemory()`. Without this split the fit would embed a 7-iteration
   Newton solve per replayed review — a catastrophic regression, and easy to miss
   because it still produces correct numbers.
3. **Only if measurements demand it:** port the reference's analytic gradients
   (`training_v7.rs`, `Dual35`-style forward-mode AD). That turns 68 replays into 1
   but is a large, subtle change; do not do it speculatively.

### 9.5 Gate

Add a benchmark note to the PR: time `FsrsOptimizer.fit` on a synthetic 200k-review
log (the `tools/stress` directory already exists for this kind of thing) before and
after. Acceptance: no worse than ~3.5× the FSRS-6 baseline at equal log size, with
the progress bar still monotonic.

---

## 10. UI, strings, stats

### 10.1 Settings (`ui/settings/StudySchedulerSection.kt`)

- No change to the Optimize flow's shape.
- The one-shot notice from §5.4 renders here as a single line under the weights
  row, dismissible: *"Your fitted FSRS-6 weights could not be carried over — FSRS-7
  uses a different model. Running on the official defaults; Optimize will refit."*
- `restore_fsrs_defaults_confirm` stays accurate as written.

### 10.2 Strings (`res/values/strings.xml`)

Add:

```xml
<string name="fsrs7_upgrade_notice">Fitted FSRS-6 weights could not be converted to
FSRS-7. Scheduling is on the official defaults; press Optimize to refit.</string>
```

Adjust the KDoc-false claims in existing strings only where they assert a count or a
90% identity. `optimize_fsrs_parameters_help` and the `stats_fsrs_*` family are
model-agnostic and can stay.

### 10.3 Stats

- `ui/stats/CardInfoSheet.kt:71` shows `"%.1f d"` for stability. Under FSRS-7 that
  is a model quantity, not a delay. Either (a) drop the ` d` unit and label it
  "stability", or (b) show `intervalForRetention(state, desiredRetention)` as
  "next interval". **Recommend (a) plus a second row for `Sf`** — it is the one
  place the new state is directly inspectable, and it costs two lines.
- `stats_stability` / `stats_stability_label` = "Stability"; `stats_stability_help`
  is currently *"Days until recall drops to the 90 percent benchmark, per card."* —
  that sentence is false under FSRS-7 and must be reworded (e.g. *"The model's
  memory-strength estimate, in days. Not the card's interval."*).
- `StatsAggregator` histograms (`MemorySection`): keep using `S`; optionally add a
  second distribution for `Sf`. Not required.
- `StatsAggregator:493` (`knowledge += scheduler.retention(...)`) is unchanged —
  it already goes through the scheduler.
- `QuestionSignals` uses `stability > 0.0` as a "has memory" test and as a lapse-risk
  input. Both stay valid; no change needed, but the constant that assumes
  `stability ≈ days` (if any) must be re-read — see `QuestionSignals` around the
  lapse term before changing anything.

---

## 11. Tests

### 11.1 New / rewritten

| Test | What it pins |
| --- | --- |
| `FsrsSchedulerTest` | §2.7 vector at `1e-4·max(1, expected)`; every one of the six rows. |
| `FsrsSchedulerTest` | `retention(state, intervalDaysFor(state)) ≈ desiredRetention` within 1e-3, across `desiredRetention ∈ {0.75, 0.9, 0.97}` and both low and high `S`. |
| `FsrsSchedulerTest` | monotonicity: `R` strictly decreases in `t`; interval strictly increases in target retention. |
| `FsrsSchedulerTest` | no same-day branch: two reviews 10 minutes apart ≠ one review then a 1-day gap, and the same-day review still changes `Sf`. |
| `FsrsSchedulerTest` | `stabilityFast == 0` is treated as `stability` and does not throw / return NaN. |
| `FsrsOptimizerTest` | 34-length bounds; `clip` enforces `w1≥w0..w3≥w2` and `w26≥max(w25,0.5)`; `lrAt` over `batch_count*epochs`; `smoothInitialStabilitiesFsrs7` monotone + clamped. |
| `FsrsOptimizerTest` | tiny synthetic histories: thin data ⇒ defaults with `steps == 0`; a clean 2-card history ⇒ `lossAfter <= lossBefore`. |
| `MigrationTest` | 26→27: both columns exist with the right nullability/default; row counts and all other columns unchanged. |
| `Fsrs7UpgradeTest` (new) | rebuild produces FSRS-7 states from an FSRS-6-shaped log; `dueAt`/`intervalDays`/`reps`/`lapses`/`leech`/`lastReviewedAt` untouched; idempotent; CAS skips a newer review; resumes from a cursor; logs-less cards get `Sf = S`. |
| `RepositorySettingsTest` | a stored 21-weight CSV ⇒ defaults, `fsrsFitVersion == 0`, model version 7, notice raised once and only if the old set was non-default. |
| `BackupRestoreRoundTripTest` | `stabilityFast` / `stabilityFastAfter` survive a round trip; an old backup without them restores and runs. |
| `AnkiSchedulingTest` | an imported review card's `S` solves back to its interval (round-trip within a day). |

### 11.2 Existing tests that will fail and must be updated

- `SpacedRepetitionTest`, `LadderStoreTest` — reference `DEFAULT_FSRS_PARAMETERS` /
  `PARAMETER_COUNT` literals.
- `FsrsImportGuardTest` — this one needs care rather than a number swap. Its
  *intent* (a card carrying memory but no `lastReviewedAt` must not be graded as
  "reviewed this instant") stays true and must keep a test. Its *mechanism* is
  FSRS-6's same-day growth floor (`assertTrue(graded.stability > imported.stability)`
  and the pinned `2.3065`). Rewrite it to assert the FSRS-7 property that actually
  protects the case: elapsed days are taken from the interval, so the update uses
  the long-gap recall rather than `t = 0`, and growth is strictly positive.
- `FsrsReplayTest`, `FsrsReplayStreamTest`, `FsrsTrainingLogStreamTest` — replay now
  carries `stabilityFastAfter`; seeded states gain a third field.
- `CalibrationTest`, `LadderPolicyTest` — any expectation pinned to FSRS-6
  predicted-recall values.
- `BackupSchedulingTest`, `ImportSchedulingTest` — new DTO field.
- `QuestionSignalsTest` — only if it pins stability-derived scores.

### 11.3 House rule

Per `AGENTS.md`: the ARM64 hosts cannot run Robolectric/SQLite tests
("native runtime is not supported on Linux (aarch64)"). Engine and optimizer tests
are pure JVM and must pass locally; the migration/DAO/backup tests are
CI-authoritative. Do not add a test dependency.

---

## 12. Docs

| File | Change |
| --- | --- |
| `README.md` | lines ~6 and ~856–858: FSRS-6 → FSRS-7, mention the two traces and that intervals are solved numerically. |
| `threshold.md` | ~37: the ladder/calibration text that leans on "stability = interval". Superseded: `main` deleted this legacy doc while the branch was open, and the deletion wins — the same statements are covered by README and `AGENTS.md`. |
| `AGENTS.md` | only if a durable rule emerges (e.g. "the FSRS engine must never call the interval solver on the training path"). Recommended: add that one line — it is exactly the kind of silent regression this file exists to prevent. |
| `Fsrs.kt`, `FsrsOptimizer.kt`, `FsrsReplay.kt`, `FsrsTuner.kt` KDoc | rewrite the FSRS-6 claims; keep the deviation lists current. |
| `SpacedRepetition.kt` | `SchedulerConfig` KDoc "21 FSRS-6 weights" → 34. |

---

## 13. Implementation order

Each phase is independently reviewable and leaves the app working.

1. **Engine + tests** (Phase A) with `PARAMETER_COUNT = 34` but *no* schema change
   yet. The app compiles; FSRS-6 state rows are read with `Sf = S`; nothing is
   migrated. Run `FsrsSchedulerTest` against §2.7.
2. **Schema + plumbing** (Phase B): entities, v27, DAOs, backup, JSON, Anki.
   `MigrationTest` + `BackupRestoreRoundTripTest` green.
3. **Weight migration** (§5.4) + notice string. `RepositorySettingsTest` green.
4. **Rebuild** (§6): `Fsrs7Upgrade` + tests, wired into `McqApplication`.
5. **Optimizer** (Phase D): the big one. Land behind the existing Optimize button;
   no migration depends on it.
6. **Perf pass** (§9) + benchmark numbers in the PR.
7. **Docs + UI polish** (§12, §10), then the PR.

Suggested branch: `feature/fsrs-7`, cut from `main`, draft PR until the owner says
go (per `AGENTS.md`).

---

## 14. Verification

```bash
. /sdcard/repo/apk-build/env.sh
cd "$MCQ_REPO" && sh gradlew :app:assembleDebug :app:testDebugUnitTest \
  $MCQ_AAPT2_OVERRIDE --no-daemon
```

Plus, because this touches migrations:

```bash
sh gradlew :app:lintDebug $MCQ_AAPT2_OVERRIDE --no-daemon
```

After the push, pull both CI artifacts into `/sdcard/Download` under the exact
names `app-debug.apk` / `app-release.apk` (they install side by side — the debug id
is `com.mcqapp.debug`).

Manual checks on device:

1. Fresh install: new cards schedule; the four grade previews differ from each
   other and from a 1-day default.
2. Upgrade install from a pre-v27 build: nothing becomes due early or late; card
   info shows a plausible `S`/`Sf` pair; Settings shows the one-shot notice.
3. Anki import of a review deck: intervals survive; the imported `S` is close to
   the interval rather than far below it.
4. Optimize on a 1k+ review library: progress advances, weights land in bounds,
   `lossAfter <= lossBefore`, and the next graded card schedules sensibly.

---

## 15. Risks

| Risk | Severity | Mitigation |
| --- | --- | --- |
| Newton solver diverges for extreme states | medium | 50-iteration bisection fallback + round + clamp; round-trip test across the retention range. |
| `Sf = S` fallback silently becomes the permanent answer | medium | the rebuild + a log counter of how many cards were rebuilt vs. left; surface in `Logger`, not in the UI. |
| Rebuild holds the write lock and starves the study loop | medium | chunked transactions, `yield()` between them, CAS writes. |
| Optimizer regression goes unnoticed | medium | §9.5 gate; the progress total is published before the first replay so a stall is visible. |
| Old 21-weight preference mistaken for a fit | low | §5.4 resets `fsrs_fit_version` and raises the notice. |
| Backup from a newer build read by an older build | low | new JSON fields have defaults; the old build ignores them and imports with `Sf = S`. |
| Tests pinned to FSRS-6 numbers | low | §11.2 list; all pure-JVM and runnable locally. |

---

## 16. Open decisions (need the owner's call)

1. **Fractional intervals.** Recommended: keep whole-day `intervalDays: Int`.
   The alternative (storing fractional days) is a schema change on
   `card_state.intervalDays`, a `DayBoundary` rewrite, an Anki-export change and a
   UI change — a much bigger project than the model upgrade, and it buys little
   because `Again` already returns inside the sitting via `relearnMs`.
2. **Cards with no review log.** Recommended: `stabilityFast = stability`, said out
   loud. Alternative: solve `Sf` so the card's stored interval lands on the
   retention target (preserves the observable schedule, but invents a fast trace);
   worth doing only if the "no log" population turns out to be large.
3. **Post-migration notice.** Recommended: a dismissible line in Settings, no
   dialog. A modal on first launch after an update, for a purely internal weight
   reset, is disproportionate.
4. **Analytic gradients.** Recommended: finite differences first, measured; only
   port `Dual35` forward-mode AD if the 200k-review benchmark is unacceptable.
5. **`Sf` in the card info sheet.** Recommended: yes (two lines) — it is the only
   user-visible proof that the fast trace exists.
6. **FSRS-7 schedule penalty.** The reference gates it behind
   `enable_sched_penalties` (default off; Anki turns it on). Recommended: leave it
   off — it needs a differentiable interval simulation we do not have, and it is not
   part of "FSRS-7 defaults".
