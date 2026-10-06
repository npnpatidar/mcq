# MCQ App — Security & Quality Audit

**Date:** 2026-02-11
**Scope:** `app/` module (201 Kotlin files, ~36.5K LOC), build config, CI workflows, manifest/resources.
**Method:** Full static read of the data, import/export, Anki, DOCX, domain, and UI layers; manifest/build/CI review; targeted verification greps for every claimed finding. No dynamic testing (ARM64 host per repo notes; CI is authoritative for DB tests).
**Verdict:** An unusually deliberate, offline-first codebase with genuinely strong adversarial-input handling. The most serious issue is a **silent data-loss bug on the backup restore path**, not a security hole. Findings below, ordered by severity.

---

## 1. Executive summary

| Area | Assessment |
|---|---|
| Functionality | Feature-rich and coherent (tests, study/SM-2, drills, mistakes, history/mastery, DOCX/JSON/APKG import, 6 export formats). One **High** data-loss bug in backup restore; one Medium stuck-UI bug after process death. |
| UI | Modern, single-activity Navigation-Compose with careful performance engineering (bulk queries through `flowOn`, dwell tick isolated from question body, delayed-visibility spinners). Main weaknesses: several >500-line god-composables and one WebView per math-bearing row. |
| Backend | There is **no backend** — the app is fully offline (Room + DataStore; the only network traffic is Coil fetching remote image URLs). Reviewed as "data layer". Transactions are used conscientiously; the gaps are unchunked `IN()` queries and unbounded in-memory reads for mistake badges. |
| Code quality | Excellent documentation culture ("why, not what" comments throughout), single-source-of-truth helpers (`ContentHash`, `SafeZip`, `renderInlineHtml`, `ChunkedQueries`), 728 JVM tests + UI tests in CI. Some dead code and a couple of oversized classes. |
| Security | Very good posture: parameterized SQL with `LIKE` escaping, bounded zip/XML/JSON parsing, sanitized HTML, hardened WebViews, gitignored signing secrets, server-checked CI masking. Residual: imported MathML is **not** attribute-sanitized (limited-impact stored JS), and remote image URLs leak the user's IP without notice. |

---

## 2. Architecture overview

Offline-first Layered MVVM:

```
ui/ (Compose screens + ViewModels, one Activity)
  └─ repository/McqRepository (Room + DataStore preferences)
domain/ (pure Kotlin: scoring, SM-2, mistakes, search, …)  ← 728 JVM tests live against this + parsers
data/ (Room entities/DAOs, import: JSON/DOCX/APKG, export: JSON/ZIP/HTML/PDF, SafeZip)
util/ (Logger, bounded IO, image decode, inline HTML)
```

Highlights: crash-resume via a single DataStore snapshot, per-`paperId` SM-2 `card_state` (schema v8, 5 hand-written migrations), content-hash dedup, SAN-style file sniffing on import (content over extension).

---

## 3. Findings

### 🔴 HIGH

**F1. Backup restore through the Import screen silently drops everything after the first paper — and all SM-2 schedules.**
`ui/importscreen/ImportViewModel.kt:217·268·345-372`
- `parseAndLoad()` previews **only** `file.papers.firstOrNull()`.
- `import()` rebuilds the file as `McqFileDto(papers = listOf(paperDto), bookmarks, attempts)` — from `original.papers.first()`. Papers `[1..]` and the document's own `scheduling` map are **discarded**.
- `Exporter.exportAll()` *does* include scheduling with an explicit comment: *"It used to be dropped, so restoring a backup silently reset every SM-2 schedule, due date and leech flag."* — the fix exists on export but the import path re-drops it.

Every picked `.json` file is routed to this preview (`LibraryScreen.kt:240`); **backup restore is the JSON pick path**. A user restoring a full backup keeps only the first paper and loses all review progress, while the README promises the opposite. Only `.apkg`/sample imports bypass the preview (`LibraryViewModel.importAnkiPackage`, `loadSampleData`) and are correct.
*Fix:* when `original.papers.size > 1` or `scheduling.isNotEmpty()`, skip the single-paper preview and import the file as-is (or make the preview multi-paper). Add a unit test at the ViewModel level — current tests exercise `Importer` directly, which is why this wasn't caught.

### 🟡 MEDIUM

**F2. Unchunked `IN (:ids)` queries overflow SQLite's bind limit on large banks.**
`ChunkedQueries.kt` exists precisely for this and is used in most places — but these sites accept unbounded lists:
- `Importer.kt:164` `getByIds(incomingIds)` for the whole file (hit on re-import of an all-duplicate paper);
- `Importer.kt:535-539` `getMatchesByContentHashes` / `getByIds` / `getForQuestions` for collision candidates;
- `ImportViewModel.refreshDuplicates()` calls `optionDao().getForQuestions(...)` raw for the whole preview;
- `McqRepository.kt:620·794·820·875` `bookmarkDao().removeAll` / `countForQuestions` for a paper's entire question set on delete.

SQLite's variable cap is 999 on older Android (minSdk 26!) and 32766 on newer. A 1,000-question re-import or a 1,000-question paper delete throws `SQLiteException` on Android 8–10. *Fix:* route every one of these through the existing `*Chunked` extensions and add a JVM test with a >999-id bank.

**F3. Process death on the Import screen = permanent loading spinner.**
`McqNavHost.kt:166-171` reads `ImportDataHolder.pendingJsonText` (a static); `ImportScreen.kt:165-178` loads only if the holder or `importText` is non-null; the state defaults to `loading = true`. After process death the holder is empty and nothing ever flips `loading` → infinite spinner with a working Back button. *Fix:* if after `LaunchedEffect` neither source produced data, set an error state ("Pick the file again") or pop the route.

**F4. MathML from imported banks is embedded verbatim into JS-enabled contexts.**
`util/ContentElements.kt:127` (in-app page) and `export/HtmlPaperWriter.kt:179` (`sb.append(element.mathml)`). `renderInlineHtml` carefully re-emits only allow-listed tags with attributes dropped — but that sanitizer is bypassed entirely for `MathElement`s; `stripMathAttributes` only normalizes the root `<math>`/`<semantics>`/`<annotation>`. An attacker-authored question bank can carry `<mi onclick=…>` or `<math onload=…>`:
- **In-app:** `script-src 'unsafe-inline'` permits event handlers. Egress is mostly sealed (CSP `default-src 'none'`, `connect-src` inherits `none`, `img-src data: file:`, navigation is vetoed, no cookies/token in origin), so impact is annoyance-class (JS loops, DOM rewrite of the card) rather than exfiltration — but the page executes attacker JS.
- **Exported HTML:** has no CSP at all and includes inline `<script>` for quiz mode, so a shared export can run arbitrary script in the recipient's browser.

*Fix:* run inner MathML tags through the same attribute-dropping canonicalization (allow-list MathML elements, emit bare `<tag>`), and add a `MathJax`-style safe-mode config or CSP to the quiz export.

**F5. Mistake badges read the entire history into memory on the Library screen.**
`McqRepository.getMistakeCounts()` / `getMistakenQuestions()` call `getAttempts()` + `getAllQuestionResults()` — every attempt *header* plus every `question_results` row (which embed full option JSON and explanations). The study-count path was fixed for exactly this reason ("the badge read used to pull every row … into memory") but mistakes kept doing it; History screen loads the same tables again. On a library with tens of thousands of results this is a real memory/latency regression. *Fix:* a SQL aggregate (e.g. `SELECT DISTINCT questionId … WHERE attempts.paperId = … AND isCorrect = 0 AND correctOptionIds != ''` plus last-finished ordering), mirroring `getGradedResultsForQuestions`.

**F6. Heavy mutating DAO loops inside single transactions.**
`moveQuestionsToCategory`, `copyQuestionsToCategory`, `bulkUpdateQuestions`, `duplicatePaper` perform per-question `getQuestion()` → `saveQuestion()` (each itself 6+ statements) inside one `withTransaction`, and `duplicatePaper`/`copyQuestionsToCategory` first materialize **all** question ids (`db.questionDao().getAll()`) to compute collision-free copy ids. Correct (crash-atomic, well-commented) but write-lock time and memory scale with DB size; duplicating a paper roughly doubles its rows inside one transaction. *Fix:* generate ids by querying only the affixed namespace, or at least chunk; keep the single transaction (correctness over speed — just note the bound).

### 🟢 LOW

- **F7. Dead/mirror code:** `Importer.kt:633` `private fun PaperDto.withDownscaledImages()` is never called — `McqFileDto.withDownscaledImages()` repeats the mapping inline. Delete one.
- **F8. Shipped "TRIAL prototype":** `MathLiveEditor` (in `BlockListEditor`, i.e. production) has `javaScriptEnabled = true`, `allowFileAccess = true` and a `@JavascriptInterface` bridge. Content is only bundled assets + `JSONObject.quote`d LaTeX, so it's currently safe, but it forfeits the `allowFileAccess=false` hardening applied to the math *preview* WebView. Either harden it the same way (assets load without file access per the preview's own comment) or finish the prototype.
- **F9. CSP inconsistency:** the math preview CSP allows `img-src: file:` while `allowFileAccess=false` makes such loads impossible. Harmless, but the CSP should mirror reality.
- **F10. Remote images leak the user (privacy):** `image`/`http(s)` fields are fetched by Coil with `INTERNET` from whoever authored the bank — the operator sees the learner's IP and roughly when they opened which question. Documented in the README; still worth a first-run notice or a "load remote images" toggle.
- **F11. `toggleBookmark` check-then-act** outside a transaction (`McqRepository.kt:906`). A double-tap within one dispatcher hop can invert twice. Cosmetic.
- **F12. `versionCode` assumes minor < 1000, patch < 1000** and that tag ordering is semver-monotonic; fine in practice for `0.x.y` but `0.0.1000` collides with `0.1.0`. One `require()` in the build script would make it fail loudly.
- **F13. Maintainability sizes:** `LibraryScreen.kt` 1280 LOC, `McqRepository.kt` 1336, `Importer` is stateful/not-reentrant (documented). All carry good comments, but they're the files most likely to grow F2/F5-class defects.
- **F14. CI hardening (supply chain):** no Gradle wrapper validation action, no dependency scanning (Dependabot/Renovate), no detekt/ktlint. For an offline app with a small, curated dep set this is low risk; for CI hygiene it's a one-file addition.
- **F15. `exportSchema = false`:** Room schema JSON isn't tracked in git. Manual migrations are tested (good), but committed schemas would let Room validate drift automatically. Low value given the tested raw-SQLite migration tests — optional.
- **F16. Import dedupe by attempt triple:** restored-attempt dedup keys on `(paperId, finishedAt, score)`; two attempts ending in the same millisecond with the same score alias (importing the same legit backup twice in the same session against a weird clock). Practically negligible.

---

## 4. Done well (worth keeping)

- **Adversarial input posture is real, not decorative:** zip bombs bounded by *measured* decompression (`SafeZip`), not trusted headers; 64 MB streaming cap on file picks (`readBounded`) so providers can't oversize the allocation; JSON depth-limited before parse (StackOverflowError → clean error); DOCX XML hardened with every factory XXE feature plus a stubbing `EntityResolver`; `.docx` detected by content, not extension; OOM `Error`s (not just `Exception`s) caught at image decode and Anki import.
- **WebView containment:** navigation vetoed, `allowFileAccess/ContentAccess` off, transparent page, CSP, `loadDataWithBaseURL(null,…)`, per-WebView `destroy()` in `onRelease` (leak fix). MathJax/MathLive are bundled — no runtime CDN dependency.
- **SQL:** fully parameterized; the `LIKE` prefilters escape `%`/`_`/`\` with `ESCAPE '\'`; the fragment prefilter trick (longest query word) to avoid false negatives against markup-embedded text is clever and correct.
- **Secrets:** never committed (`git log` clean), env-first with gitignored `keystore.properties` fallback, `::add-mask::` for CI, best-effort masking, key removed `if: always()`.
- **User data:** database/logs excluded from cloud backup and device transfer; FileProvider scope limited to the log dir; only INTERNET permission; exported Activity is launcher-only (no data-accepting intents); a single intentionally-delimited `FileProvider`.
- **Testing & docs:** 728 JVM tests incl. a 1000-case seeded fuzzer for the parser, a raw-SQLite migration test written specifically because ARM64 hosts can't run Room/Robolectric (environment-vs-code distinction documented), emulator UI tests for the critical path, README documents gotchas *it observed*, not theory.

---

## 5. Recommended order of work

1. **F1** — fix backup restore (data loss). Add a VM-level test with a multi-paper + scheduling backup through the preview.
2. **F2** — chunk the remaining `IN` queries; add a >999-question regression test.
3. **F3** — null-source guard in the Import screen.
4. **F4** — sanitize MathML internals; CSP for quiz exports.
5. **F5/F6** — move mistake counting into SQL; bound the duplicate/copy id scans (perf only; correctness is fine).
6. F7–F16 as cleanup; add `gradle/actions/wrapper-validation` to CI.

*No commits made; report only.*
