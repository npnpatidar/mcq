# Phase D report — database integrity, migrations and derived data

**Reviewer:** deep data-integrity audit. **Read-only.** Per-screen data findings are not repeated.

## Findings

| Sev | Finding | Evidence / fix |
|---|---|---|
| Medium | Cross-paper re-key is source-agnostic: `INSERT OR IGNORE … SELECT` has no source-paper filter/ORDER BY, and review-log re-key UPDATEs all papers. No schema UNIQUE on `questionId`, so a question with `card_state` in two non-target papers (corrupt restore / partial earlier move) pairs arbitrary memory with merged history. | `Daos.kt:1729-1739,2204-2208`, `QuestionStore.kt:526-548`. Derive current paper from `categories` and re-key only that source. **Owner: supported state or corruption to reject?** |
| Medium | Attempt-delete recompute re-points `question_stats.paperId/title` from the surviving result's attempt, undoing cross-paper move re-pointing. Latent: `deleteAttempt` has no production caller. | `Daos.kt:3104-3131`, `QuestionStore.kt:554-558`, `HistoryStore.kt:908-914`. Preserve current display fields via COALESCE. **Owner: expose attempt deletion or retire as test-only?** |
| Low | `StudyStore.staleChecked` process cache not cleared on restore; stale-hash repair skipped until restart. Mitigated by query-time hash predicate. | `StudyStore.kt:57,309-317`, `McqRepository.kt:607-626`. Clear in restore `finally`. |
| Nit | Stale migration comment says attempt delete does not shrink counters (now recomputes); orphaned partial-index KDoc above wrong migration. | `AppDatabase.kt:410-416,712-718`. |

## Checked with no defect

Entity/schema parity for all 20 tables; intentional unenforced FK invariants compensated by manual sweeps at every delete site; full migration chain 3→28 with correct dual-shape v27 guards (`sqlite_master` + `PRAGMA table_info`) and discarded ladder history documented; bounded keyset backfills; calibration cache key/generation closure; exposure counts derived from `review_log` with no counter; FSRS rebuild three-field CAS with resumable cursor; content-hash scaled twin and passage rehash; backup wipe-and-replay rebuilds question_tags and question_stats; v2 composite NUL key + bare alias through one helper; partial index dual path satisfies Room validation.

## Test gaps / owner decisions

Add migration test for two source-paper card_state rows, attempt-delete display-paper preservation (if exposed), restore stale-hash repair. All are Robolectric/SQLite — CI x86_64 authoritative. Decisions as in the table.
