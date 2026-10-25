# Twelfth bug report — scoped pass: maths tables and inline formatting (underline, highlight, …)

A scoped pass over the rich-content feature — **status:** report only, nothing
fixed yet. Files covered: the element model (`Models.kt`), the shared inline
renderers (`InlineHtml.kt`, `RichTextHtml.kt`), the WebView mixed view
(`ContentElements.kt` incl. `stripMathAttributes`), the import readers
(`DocxReader.kt`, `DocxParser.kt`, `AnkiPackageReader.kt`, `AnkiHtml.kt`,
`LegacyParser.kt`), the editor's table/math blocks (`EditorViewModel.kt`,
`BlockListEditor.kt`), and all four paper exporters (PDF, DOCX, HTML, Anki
media pool).

Every defect marked **Confirmed (run)** is proven by
`app/src/test/java/com/mcqapp/ContentRenderProbeTest.kt` (untracked, evidence
only — 9 tests, all passing locally). PDF-span items are confirmed by reading
but need Robolectric (aarch64-host blocked; CI authoritative).

The core data fact the findings rest on: **table cells are stored as strings
that deliberately carry inline markup.** The DOCX reader builds them from
`paraHtml` (DocxReader.kt:499-522 — formatting, images and OMML math
included), the Anki reader's `parseTable` documents "inline formatting inside
cells survives" (AnkiPackageReader.kt:1094), and the Anki *export* renders
them (`tableCellsRenderInlineTags`, AnkiMediaPoolTest.kt:31-37). Yet four of
the six consumers treat cells as plain text — search strips markup
(QuestionSearchTest pins preview "cell one"), so the browse list shows "cell
one" while the question itself shows "cell <i>one</i>".

**Confidence legend:** **Confirmed (run)** = proven by the probe test;
**Confirmed** = defect proven by reading the current code; **Likely** = code
defect real, one reachability step depends on data shape.

---

## Summary

| # | Sev | Area | Bug | Conf. |
|---|-----|------|-----|-------|
| 1 | High | In-app table | Table cells render raw markup as literal text — `<u>`, `<mark>`, `<strong>`, `<sub>` show as tags | Confirmed |
| 2 | High | In-app table | A picture in a DOCX table cell renders as its full base64 text blob | Confirmed (run) |
| 3 | High | In-app table | A "maths table" (equation in a cell) renders raw MathML markup as text | Confirmed (run) |
| 4 | Medium | Import | DOCX merged cells (`w:gridSpan`) are imported as one cell — every later cell of that row shifts a column left | Confirmed (run) |
| 5 | Medium | PDF export | Mis-nested closes are dropped, not popped: `<u>a <b>b</u> c</b>` loses the underline and leaks bold onto " c" — in-app pops-until-found, DOCX toggles flags, HTML re-emits | Confirmed |
| 6 | Medium | DOCX export | `tableXml` writes cells as escaped plain text: Word shows literal `<u>x</u>`, underline/highlight/sub/sup lost (PDF renders the same cell formatted) | Confirmed |
| 7 | Low | Mixed view | `stripMathAttributes` keeps only the first `<math>` block — text around it in the same MathElement is silently dropped | Confirmed (run) |
| 8 | Low | In-app vs exports | Unknown tags: the screen strips them, every exporter keeps them visible — and `InlineHtml`'s own doc says the opposite of its code | Confirmed (run) |
| 9 | Low | Mixed view | `$…$` TeX delimiters are enabled for body text: any imported sentence with two `$` typesets the text between them as garbled math | Likely |
| 10 | Nit | In-app table | Newlines in a cell: native view shows a line break, mixed view and HTML export collapse it to a space | Confirmed (run) |
| 11 | Nit | In-app/PDF vs DOCX | Consecutive `<br>`s collapse to one newline on screen, but the DOCX writer emits a `w:br` per tag — exports gain a blank line the screen never shows | Confirmed (run) |
| 12 | Nit | PDF export | Table column widths are measured on entity-encoded text (`&` counts as 5 chars) while cells render decoded | Confirmed |
| 13 | Nit | Editor | `addTableRow` sizes the new row from the *first* row; on a ragged (merged-cell) import the new row mismatches the rest; `updateTableCell` silently no-ops on short rows | Confirmed |

---

## Findings

### 1 · HIGH · In-app table — cell markup shows as literal tags

`TableElement` renders each cell with a bare `Text(cell)` (ContentElements.kt:100-107).
Cells carry markup by design (see intro), so a Word cell that is underlined,
highlighted, bold, subscripted… imports as `<u>u</u><mark>m</mark>` (probe:
`underlinedCellImportsAsRawMarkup`) and the screen shows the tags themselves.
The mixed view is no better: `escapeHtmlData(cell)` (ContentElements.kt:189)
escapes the markup into visible text (probe:
`mixedViewEscapesCellMarkupInsteadOfRenderingIt`), and the HTML paper export
does the same with `esc(cell)` (HtmlPaperWriter.kt:253). Only the PDF writer
renders cell formatting (`spannedFromInlineHtml`, PdfPaperWriter.kt:510) and
only the Anki export does (`AnkiMediaPoolTest` pins `<td>H<sup>2</sup></td>`).

So the same imported table shows four different things: formatted (PDF),
literal tags (screen, HTML export), escaped tags (mixed view), and after a
DOCX round-trip, literal tags inside Word itself (finding 6).

**Fix.** Render cells through the shared inline renderers — `InlineHtml.parse`
in the native table, `renderInlineHtml` in the mixed view (it allow-lists
tags and drops attributes, so the JS-enabled page stays safe) and in the HTML
export, `inlineRuns` in the DOCX writer. Findings 2/3 (image and math in
cells) need the cell model to split blocks out of the string, or at minimum a
visible placeholder instead of markup soup.

### 2 · HIGH · In-app table — a picture in a cell renders as a base64 blob

The DOCX reader emits `<img src="data:image/png;base64,…">` for an embedded
picture inside a table cell (DocxReader.kt:388-394 → 433-466 →
`tableRows`), and the cell string keeps it. Both in-app table paths then
render it as text: native `Text(cell)` shows the entire base64 payload
(ContentElements.kt:100), and the mixed view escapes it into an even longer
literal string (ContentElements.kt:189). Probe:
`pictureInTableCellImportsAsBase64Text`. A one-photo table cell displaces
pages of text.

**Fix.** With finding 1's model change (cells as element lists) the image
renders via the existing `ImageElement` path. A cheaper stopgap: strip
`<img>` tags from cell display and show a `[image]` marker.

### 3 · HIGH · In-app table — equations in cells show as raw MathML

An OMML equation inside a DOCX table cell becomes `<math>…</math>` text in
the cell (DocxReader.kt:395-409, probe: `mathInTableCellIsRawMarkup`). Neither
table path routes it to MathJax: the native view shows the markup, and the
mixed view — the one path that *does* typeset MathML — only processes
`MathElement`s, never cell strings, and escapes the cell instead
(ContentElements.kt:184 vs 189). The PDF writer is also bare here:
`spannedFromInlineHtml` knows no `<math>` tag, so the exported paper shows
raw MathML in the cell too (PdfPaperWriter.kt:510, 623-662). A maths table
imported from Word is unreadable in-app and in PDF.

**Fix.** Same model change as findings 1-2; for the mixed view specifically,
cell math could be emitted through `stripMathAttributes` so MathJax typesets
it in place.

### 4 · MEDIUM · Import — merged DOCX cells shift every later cell a column left

`tableRows` ignores `w:gridSpan` (DocxReader.kt:499-522): a row whose first
cell spans two grid columns imports with one fewer cell than the grid, and
every later cell of that row lands one column left of the data it belongs to
— in-app (both table renderers lay cells positionally), in PDF
(PdfPaperWriter.kt:469-514), and in re-exports. Probe:
`gridSpanMergedCellShiftsColumns` (row `[H1(span2), H2]` imports as
`[H1, H2]` next to `[A, B, C]` — H2 sits over B's column). Word's vertical
merge is safe (`vMerge` continues are separate empty `w:tc`s); only the
horizontal case shifts.

**Fix.** Read `w:gridSpan w:val` in `tableRows` and pad with empty strings so
every row has grid-column positions; the renderers already cope with ragged
rows via `getOrElse`.

### 5 · MEDIUM · PDF export — mis-nested inline tags lose formatting

`spannedFromInlineHtml` only honours a close when it matches the *top* of its
open stack; anything else is silently dropped (PdfPaperWriter.kt:638-646).
For `<u>a <b>b</u> c</b>` (real Anki decks and hand edits produce exactly
this): `</u>` mismatches the top (`b`), so **no underline span is ever
emitted**, and `</b>` then sets bold from its open position — over " c" too.
The in-app renderer fixed this exact class with a bounded pop-until-found
(InlineHtml.kt:91-104, pinned by `aMisnestedCloseEndsItsSpansThere`), and the
DOCX writer's flag machine toggles off per tag (DocxPaperWriter.kt:668). Three
renderers, three answers for the same stored text — and the PDF one is
strictly worst, deleting a formatting the other two keep.

**Fix.** Mirror `InlineHtml.parse`: on a close, pop until the matching open
(closing the spans above it), and set each span at its own close. Needs a
Robolectric regression test (CI).

### 6 · MEDIUM · DOCX export — table cells lose all inline formatting

`tableXml` writes each cell with a single escaped `w:t` (DocxPaperWriter.kt:585-592);
`inlineRuns` — which exists and works, markup to `w:u`/`w:highlight`/`w:vertAlign`
and all — is never applied to cells. So a table whose PDF export underlines
"u" and highlights "m" produces a Word file whose cells read literally
`<u>u</u><mark>m</mark>`. (The payload fingerprint survives — the re-imported
literal string matches the exported one — so this corrupts the visible Word
document, not the data.)

**Fix.** Run cell strings through `inlineRuns`/`ParaBuilder` in `tableXml`.

### 7 · LOW · Mixed view — text around a `<math>` block is dropped

`stripMathAttributes` extracts the *first* `<math>…</math>` block and discards
everything before/after it (ContentElements.kt:295-300). Probe:
`stripMathAttributes("Solve <math><mi>x</mi></math> now")` renders just the
formula — "Solve" and "now" vanish. Reachable through the structured import's
`{"type":"math","content":…}` shape, which is taken verbatim
(LegacyParser.kt:1427-1430); only the plain-`"text"` path splits runs on
`<math>` (`splitTextRuns`). Importers currently produce pure blocks, so this
is latent — but the fallback comment on line 297-299 explicitly worries about
hostile/malformed content, and silently *deleting* prose is the wrong failure
mode for it.

**Fix.** Emit the surrounding text (escaped) around the wrapped math, the way
`splitTextRuns` does one level up.

### 8 · LOW · Screen vs exports — unknown tags disagree, and the doc lies

`InlineHtml.parse` strips unknown tags (InlineHtml.kt:83-89; pinned by
`unknownTagsAreStripped`) — probe: `InlineHtml.parse("Pick <one>?")` →
"Pick ?". Every exporter deliberately keeps them as visible literal text
(`renderInlineHtml` RichTextHtml.kt:24-27, PDF PdfPaperWriter.kt:651-655,
DOCX DocxPaperWriter.kt:670-672, Anki pool pinned by
`unknownTagsAreEscapedNotDropped`), justifying it as "must stay visible
rather than vanish — the rule … the in-app renderer" follows. But the in-app
renderer vanishes them. The class doc itself says "Anything unrecognized … is
left visible rather than swallowed" (InlineHtml.kt:19-21) — stale against its
own test. Net effect: `Pick <one> & "all"?` reads "Pick  & \"all\"?" on the
screen and "Pick <one> & "all"?" in all four exports and any re-import.

**Fix.** Either render unknown tags literally in-app (matching the doc and
all exporters — careful: that also makes `<font size=4>`-style wrapper noise
visible, which the strip was likely added for) or fix the doc and the
exporters' comments to own the divergence.

### 9 · LOW · Mixed view — `$…$` delimiters mangle ordinary text

The mixed page enables single-`$` inline math: `MathJax = { tex: { inlineMath:
[['$', '$']] } }` (ContentElements.kt:212). Body text is escaped only for
`<>&"` (`renderInlineHtml`), so an imported sentence like "He paid $5 and got
$10 back" typesets "5 and got " as garbled italic math. Single `$` alone is
harmless, which is why this survives casual testing. (The HTML paper export
does not enable `$…$`, so the web page and the screen also disagree here.)

**Fix.** Drop the `$` delimiter (MathML is the app's input format; TeX
authoring happens in MathLive), or escape `\$` in text.

### 10 · NIT · In-app table — cell newlines diverge between the two views

A cell with an embedded line break (DOCX `w:br` → `\n`, DocxReader.kt:378)
renders as two lines in the native table (`Text`) but as one collapsed line
in the mixed view and the HTML export — only `TextElement`s get the
`\n → <br/>` pass (ContentElements.kt:183), cells don't (line 189, probe
`mixedViewKeepsRawNewlineInTableCell`).

### 11 · NIT · Double `<br>` — screen collapses, DOCX export doesn't

In-app `appendBreak` suppresses a second consecutive break (InlineHtml.kt:73-75;
probe: `a<br/><br/>b` → "a\nb") and the PDF writer collapses the same way
(PdfPaperWriter.kt:634-635). The DOCX writer emits one `w:br` per tag
(DocxPaperWriter.kt:707-710), so the same stored text exports a blank line
the screen never showed.

### 12 · NIT · PDF export — table columns measured on encoded text

`table()` sizes columns with `stripInlineHtml(cell)` (PdfPaperWriter.kt:477),
which strips tag shapes but not entities; cells render decoded
(`decodeEntities` at PdfPaperWriter.kt:629). `5 < 6` measures ~7 chars and
renders 5 — entity-heavy cells get over-wide columns.

### 13 · NIT · Editor — ragged tables and silent cell no-ops

`addTableRow` sizes the new row from `rows.first()` (EditorViewModel.kt:318-321),
so on a merged-cell import (finding 4) the appended row is shorter than the
grid; `updateTableCell` silently ignores a `col` past the row's end
(EditorViewModel.kt:309-316), so tapping a cell in a short row edits nothing.
Use the max row width and pad short rows on edit.

---

## Not bugs (checked and deliberately left alone)

- **Cell markup round-trips byte-stably through DOCX export/re-import** — the
  escaped literal text reads back as the same string, so the payload
  freshness check never false-positives (finding 6 is purely visual).
- **`<u>`-wrapped `<img>`/`<math>` in a formatted DOCX run** (`runHtml`
  wraps the whole run's html, DocxReader.kt:414-422) — `oMath` inside `w:r`
  is not valid OOXML and Word/LibreOffice don't emit it; the branch is
  defensive.
- **`findClosingTag` depth accounting** (AnkiPackageReader.kt:1073-1092) —
  the opener matching itself in iteration 1 is what balances `depth` to 0;
  traced and correct.
- **Anki unclosed `<math>` swallowing the field tail** — malformed only if a
  foreign deck writes it; the DOCX path's "stays text" rule is the stricter
  one, but changing the Anki side to match risks more than it fixes.
- **`mark` = hard-coded yellow/black** in both renderers (InlineHtml.kt:43,
  PdfPaperWriter.kt:608) — readable in dark mode; matches Word's
  `w:highlight yellow` round-trip.
- **`w:shd` shading as highlight** — Word's shading is a background fill
  with dozens of fill colours; treating it all as `mark` would over-highlight.
  Only `w:highlight` is claimed, and that matches the writer.
- **`sanitizeMathmlReporting`'s early-return skipping the `mrow` wrap**
  (ContentElements.kt:311-315) — pinned by `mathmlUnknownTagsAreEscaped`;
  MathJax accepts bare `<mi>` under `<math>`.
- **`nbsp` decoding to a plain space in `InlineHtml`** vs `\u00A0` in
  `RichTextHtml`/PDF — inconsistent, but unobservable in rendering.
- **Editor `blockKey` position-based identity** — documented trade-off
  (BlockListEditor.kt:159-171); keyed-on-content had a worse failure.
