# Sixth bug report — scoped pass: DOCX import and export

A scoped pass over the DOCX path — **status:** fixes for #1-4 were applied
after this report; #5 remains by design. Files covered: the zip/XML reader
(`DocxReader.kt`), the marker→JSON parser
(`DocxParser.kt`, including the hidden-payload freshness machinery), the
writer (`DocxPaperWriter.kt`, `MediaSpool.kt`, `ExportImages.kt`), the OMML
converters' entry conditions, the XML helpers (`escapeXmlText`,
`decodeXmlEntities`), and the pick-routing classifier (`PickedArchive.kt`,
`isDocxArchive`).

**Confidence legend:** **Confirmed** = defect proven by reading the current code
and the exact input · **Likely** = code defect real, one reachability step
depends on data shape.

---

## Summary

| # | Sev | Area | Bug | Conf. |
|---|-----|------|-----|-------|
| 1 | Medium | Import | Unaccepted tracked-change insertions (`w:ins`, `w:moveTo`) are dropped silently; deletions (`w:del`) are kept as struck text | Confirmed |
| 2 | Low | Import routing | Byte-overload `isDocxArchive` still counts directory entries — the exact bug the streaming classifier fixed; a drift trap | Confirmed (latent) |
| 3 | Low | Export | `jpegSize` treats length-less JPEG markers as length-bearing (RST 0xD0–0xD7, TEM 0x01) and reads garbage lengths; SOS (0xDA) is read as a size segment | Confirmed (cosmetic) |
| 4 | Low | Export | A question's passage context is silently dropped when its `passageId` resolves to no passage in the stream (no warning, unlike every other uncarryable thing) | Likely |
| 5 | Low | Export | Category depth clamped to Heading 9: depth ≥ 9 (Heading 2 depth 7 +) nests wrong on a visible-only re-import; fresh payload keeps ids/parents | Confirmed (by design; noted) |

---

## Findings

### 1 · MEDIUM · DocxReader — tracked-change insertions vanish

`app/src/main/java/com/mcqapp/data/docx/DocxReader.kt` (`BodyReader.paraHtml`,
the paragraph-child `when`): `w:del` is unwrapped and rendered as
`<del>…</del>`, but its insertion counterparts `w:ins` and `w:moveTo` fall into
the catch-all `else` branch ("no content") along with `w:fldChar` and friends —
so every run an unaccepted inserted revision carries is **dropped from the
imported text** with no warning.

A document edited in Word with Track Changes on — the ordinary way someone
fixes an option in a shared bank — then imported without accepting the
revisions, imports the pre-edit text while the screen shows no hint anything
was lost. Worse shape than the deleted-text case, which is at least kept
(strikingly visible) even though an unaccepted deletion arguably should not
carry into the bank either.

**Fix:** unwrap `w:ins`/`w:moveTo` like `w:del` but keep the runs as plain
content; refuse `w:del`/`w:moveFrom` content (dropped with the existing
warning style), so the bank agrees with what Word's *accepted* document would
hold. The producer side (`DocxPaperWriter`) cannot hit this: the app's
documents carry no revisions.

### 2 · LOW · Import routing — the byte classifier drifted from the streamed one

`app/src/main/java/com/mcqapp/data/docx/DocxParser.kt:42` (`isDocxArchive`)
counts every non-null entry toward its 12-entry cap; the streaming classifier
in `PickedArchive.kt` counts only *files* (a directory-counting entry pushed a
real Word bank past the cap and mis-filed it as Anki — an earlier fix, pinned
by `PickedArchiveTest`). Production routes through the streamed classifier
only, so today this is latent: the byte overload is kept as a public decision
API (tests use it as an oracle), and it silently disagrees with the rule it is
supposed to mirror on a directory-padded archive.

**Fix:** count only non-directory entries in the byte overload, same rule.

### 3 · LOW · Export — `jpegSize` walks the marker stream unsoundly

`DocxPaperWriter.kt` (`jpegSize`): the loop treats every marker outside
`0x01..0x09 | 0x64 | 0xD8` as length-bearing, but RSTs (`0xD0..0xD7`), EOI
(`0xD9`) and TEM (`0x01`) carry no length: the loop reads the next two payload
bytes as a length and desyncs. `SOS` (`0xDA`) — inside the `0xC0..0xCF` SOF
range test — is parsed as if it carried dimensions, yielding header junk that
normally fails the 1..1,000,000 sanity bound and degrades the drawing to the
320×240 default. On well-formed files the scan always reaches a real SOF
before either case, so the practical effect is cosmetic (wrong/absent sizing
on malformed JPEGs only) — recorded for completeness, fix is a marker-skipping
table.

### 4 · LIKELY · Export — passage context lost without a warning

`DocxPaperWriter.writeBody`'s `passageAbove`: when `question.passageId` names
no passage in `stream.passages` (a passage deleted after the questions that
reference it, or a foreign/incomplete stream), the question is emitted without
any `Passage:` block — visible-only re-import loses its membership — with no
warning, unlike options, images and every other uncarryable artifact the
writer reports. A fresh payload preserves the id, which is why this stayed
latent. **Fix:** add the warning.

### 5 · Confirmed, by design — Heading-9 depth clamp

`writeBody`'s `onCategory` clamps the heading level at 9; `headingLevelOf` maps
level 9 back to depth 7, so a category tree deeper than that (which the JSON
format is not bounded from carrying) re-imports flattened, one sibling at the
same depth, through the *visible markers only*. The fresh payload path
preserves ids and parents, so this only bites markers-only paths (edited
files). Recorded so the depth bound is a decision, not a surprise.

---

## Areas checked, clean

1. **Marker grammar ownership** — writer's line guard and the parser's grammar
   share one regex (`docxLineStartMarker`), so a round trip cannot reopen the
   `(a)`–`(z)` guard gap of `new_bugs.md` #2; option markers are capped at ten
   on both sides (`take(10)` in `canonQuestion`, `OPTION_LETTERS` in the
   writer) and out-of-range letters are refused at import, not silently
   renumbered.
2. **Payload freshness** — the subsequence walk keys `matchedVisible` by
   *payload position*, which grafting iterates in the same order; skipped
   (marker-uncarryable) payload questions land on the gap and are grafted
   unaffected. Title, passages (content-canonical, order-independent),
   option-answer resolution ("0" mirror for answerless questions), the
   ten-option cap, blank-field canons and image position markers all line up
   with the writer's stripping rules (`stripPayloadImages` mirrors
   `canonField`'s scalar-position marker).
3. **XML safety** — `escapeXmlText` strips control characters, lone
   surrogates and `\uFFFE/F`; `XmlEscapingAppendable` escapes chunk-wise
   without splitting an entity; the reader hardens its factory (best-effort
   per feature) and its entity resolver resolves nothing, so XXE is closed
   even when `disallow-doctype-decl` is unavailable (Android).
4. **Zip safety** — both archive decoders bound the decompression with
   `SafeZip`; the reader tells a too-large archive apart from a corrupt one.
5. **Media** — `MediaSpool` spools to temp files with an injective
   percent-encoding name map; the content-types Defaults cover every type
   `imageExtension` can emit (jpg/png/webp/gif only); dedupe keys are SHA-256s
   of the URI, not the URI.
6. **Image sizing** — the EMU math is long-based and clamped twice (the
   225,443-px overflow from `new_bugs.md` #19 is fixed); dims are bounded to
   1..1,000,000 per axis, with BMP's negative-height case taken as magnitude.
7. **OMML round trip** — `m:oMathPara` (Word's wrapper for display equations)
   recuses into its `m:oMath`, so native Word display equations are not
   degraded to linear text; paragraph-level and run-level `m:oMath` both reach
   the converter; failed conversions degrade to linear text *with* a warning
   on both directions.
8. **Sectioned import** — `Heading_20_1` LibreOffice spellings, `Title` as
   level 1, per-section passage-id qualification, the pre-heading
   "Uncategorized" section, and the paper-title-from-first-Heading-1 rule all
   round trip against the writer's emission (title at level 1, first category
   at Heading 2).
9. **Answer forms** — letters path first, whole-text match including the
   option text containing separators, mixed letters+text refused with the
   offending answer quoted, zero-based index kept for the legacy shape.
10. **Passage anchors** — the blanked-in-place rebuild keeps every character
    offset, so anchor positions stay valid in the rebuilt text; a bare
    `Passage:` reset is round-tripped by a bare reset paragraph in the writer.
11. **Streamed classification** — `classifyPickedArchive` counts files, not
    entries; a truncated archive answers from the prefix it read; the backup
    docx/manifest decision reflects the byte checks' order.
