# Review Coverage Ledger

Branch: `feature/full-app-audit-plan` (based on main `ba79e24`, release 0.0.53).

Status values: `PENDING` → `IN REVIEW` → `DONE` / `DONE (no defect)` / `FINDINGS`.
Only a reviewed row with a batch id and reviewer may be marked DONE.

| Area | File | Lines | Status | Batch | Reviewer | Findings / follow-up |
|---|---|---:|---|---|---|---|
| Production Kotlin | `app/src/main/java/com/mcqapp/MainActivity.kt` | 79 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/McqApplication.kt` | 109 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/RichTextHtml.kt` | 259 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/SafeZip.kt` | 165 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/anki/AnkiDtoMapper.kt` | 61 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/anki/AnkiHtml.kt` | 205 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/anki/AnkiMcqPayload.kt` | 116 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/anki/AnkiMediaPool.kt` | 137 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/anki/AnkiPackageReader.kt` | 1606 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/anki/AnkiPackageWriter.kt` | 488 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/anki/AnkiScheduling.kt` | 294 | FINDINGS | C | deep reviewer | review-reports/15-c-domain.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/anki/AnkiSchema11.kt` | 350 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/anki/AnkiTempFiles.kt` | 48 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/backup/Backup.kt` | 1368 | FINDINGS | D | deep reviewer | review-reports/16-d-data-integrity.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/backup/JsonValueStream.kt` | 253 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/docx/DocxParser.kt` | 1081 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/docx/DocxReader.kt` | 545 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/docx/MathMlToOmml.kt` | 259 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/docx/OmmlToMathMl.kt` | 144 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/export/DocxPaperWriter.kt` | 1181 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/export/ExportFormat.kt` | 61 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/export/ExportImages.kt` | 65 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/export/HtmlPaperWriter.kt` | 348 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/export/MediaSpool.kt` | 82 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/export/OptionLetter.kt` | 35 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/export/PaperExporter.kt` | 198 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/export/PaperJsonWriter.kt` | 143 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/export/PaperStream.kt` | 90 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/export/PdfMath.kt` | 411 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/export/PdfPaperWriter.kt` | 756 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/export/ReportCsv.kt` | 79 | FINDINGS | B4 | parallel reviewers | review-reports/04-results.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/export/ReportPdf.kt` | 202 | FINDINGS | B4 | parallel reviewers | review-reports/04-results.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/export/ZipPaperWriter.kt` | 98 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/io/AnkiApkgImporter.kt` | 95 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/io/BookmarkExport.kt` | 35 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/io/CardContent.kt` | 179 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/io/CardScheduleMapping.kt` | 99 | FINDINGS | C | deep reviewer | review-reports/15-c-domain.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/io/CategoryFilter.kt` | 23 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/io/ContentHash.kt` | 209 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/io/Exporter.kt` | 540 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/io/ImageDownscale.kt` | 113 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/io/ImportPaperMatch.kt` | 48 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/io/ImportPreview.kt` | 103 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/io/ImportRunJournal.kt` | 93 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/io/Importer.kt` | 1092 | FINDINGS | D | deep reviewer | review-reports/16-d-data-integrity.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/io/JsonDto.kt` | 261 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/io/JsonPull.kt` | 309 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/io/LegacyParser.kt` | 1584 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/io/QuestionDtoMapper.kt` | 36 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/io/SelectionExport.kt` | 66 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/io/StreamingImportSession.kt` | 1008 | FINDINGS | D | deep reviewer | review-reports/16-d-data-integrity.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/io/StreamingImporter.kt` | 221 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/local/AppDatabase.kt` | 1279 | FINDINGS | D | deep reviewer | review-reports/16-d-data-integrity.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/local/ChunkedQueries.kt` | 471 | FINDINGS | D | deep reviewer | review-reports/16-d-data-integrity.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/local/Daos.kt` | 3643 | FINDINGS | D | deep reviewer | review-reports/16-d-data-integrity.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/local/Entities.kt` | 705 | FINDINGS | D | deep reviewer | review-reports/16-d-data-integrity.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/local/PassageEntity.kt` | 50 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/local/QuestionPlacement.kt` | 50 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/repository/Fsrs7Upgrade.kt` | 218 | FINDINGS | C | deep reviewer | review-reports/15-c-domain.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/repository/FsrsTuner.kt` | 269 | FINDINGS | C | deep reviewer | review-reports/15-c-domain.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/repository/GradingRulesStore.kt` | 278 | FINDINGS | C | deep reviewer | review-reports/15-c-domain.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/repository/HistoryStore.kt` | 1055 | FINDINGS | D | deep reviewer | review-reports/16-d-data-integrity.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/repository/InstallFreshness.kt` | 131 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/repository/McqRepository.kt` | 2422 | IN REVIEW | B1 | parallel reviewers | partial: Library/delete wrappers |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/repository/PaperStore.kt` | 457 | IN REVIEW | B1 | parallel reviewers | partial: cascade paths only |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/repository/PassageStore.kt` | 246 | FINDINGS | B9 | full-lens reviewer | review-reports/09-passages.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/repository/QuestionContentMapper.kt` | 107 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/repository/QuestionStore.kt` | 743 | IN REVIEW | B1 | parallel reviewers | partial: delete paths only |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/repository/RepositorySettings.kt` | 960 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/repository/SignalStore.kt` | 126 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/repository/StatsStore.kt` | 865 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/repository/StudyStore.kt` | 635 | FINDINGS | D | deep reviewer | review-reports/16-d-data-integrity.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/data/repository/TestProgressStore.kt` | 204 | FINDINGS | B3 | parallel reviewers | review-reports/03-test-session.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/AnswerReview.kt` | 55 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/AutoAdvance.kt` | 15 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/Blueprint.kt` | 57 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/BlueprintQuotas.kt` | 313 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/BulkOps.kt` | 19 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/CategoryCounts.kt` | 82 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/CategorySelection.kt` | 229 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/DayBoundary.kt` | 61 | FINDINGS | C | deep reviewer | review-reports/15-c-domain.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/Drill.kt` | 32 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/ExamMode.kt` | 19 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/ExamTiming.kt` | 93 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/Feedback.kt` | 33 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/Fsrs.kt` | 750 | FINDINGS | C | deep reviewer | review-reports/15-c-domain.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/FsrsOptimizer.kt` | 740 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/ImportWarnings.kt` | 76 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/Marks.kt` | 43 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/Mastery.kt` | 54 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/Mistakes.kt` | 43 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/Models.kt` | 454 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/Panel.kt` | 30 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/PassageBlocks.kt` | 64 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/PassageReorder.kt` | 142 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/QuestionSearch.kt` | 126 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/QuestionStats.kt` | 58 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/Reorder.kt` | 31 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/ReportEvidence.kt` | 256 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/ReviewFilters.kt` | 41 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/ScopeKey.kt` | 65 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/Scoring.kt` | 60 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/SessionClock.kt` | 19 | FINDINGS | B2 | parallel reviewers | review-reports/02-study.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/SessionOrder.kt` | 442 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/SessionReviewPagingSource.kt` | 45 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/Shuffle.kt` | 48 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/SpacedRepetition.kt` | 673 | FINDINGS | C | deep reviewer | review-reports/15-c-domain.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/StorageInfo.kt` | 54 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/Submission.kt` | 40 | FINDINGS | B3 | parallel reviewers | review-reports/03-test-session.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/SubmitSummary.kt` | 60 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/Tags.kt` | 38 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/TestPlan.kt` | 332 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/TestProgress.kt` | 188 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/TestReport.kt` | 1395 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/TestSetup.kt` | 219 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/TestSnapshot.kt` | 237 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/TestTiming.kt` | 62 | FINDINGS | B3 | parallel reviewers | review-reports/03-test-session.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/TestType.kt` | 94 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/Trends.kt` | 60 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/signals/QuestionSignals.kt` | 337 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/signals/TestRanking.kt` | 67 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/signals/TestSelector.kt` | 1474 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/stats/Calibration.kt` | 937 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/stats/CoverageStats.kt` | 57 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/stats/ExamStats.kt` | 479 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/stats/FsrsReplay.kt` | 286 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/stats/Reliability.kt` | 335 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/stats/StatsAggregator.kt` | 2601 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/domain/stats/StatsModels.kt` | 917 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/ViewModelFactories.kt` | 80 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/browse/BrowseScreen.kt` | 1318 | FINDINGS | B5 | full-lens reviewer | review-reports/05-browse.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/browse/BrowseViewModel.kt` | 1021 | FINDINGS | B5 | full-lens reviewer | review-reports/05-browse.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/browse/BrowseWindow.kt` | 42 | FINDINGS | B5 | full-lens reviewer | review-reports/05-browse.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/common/AttemptLabels.kt` | 75 | FINDINGS | E | deep reviewer | review-reports/17-e-design-a11y-strings.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/common/PagingStates.kt` | 69 | FINDINGS | E | deep reviewer | review-reports/17-e-design-a11y-strings.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/common/StatsRangeChips.kt` | 51 | FINDINGS | E | deep reviewer | review-reports/17-e-design-a11y-strings.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/common/TestTypeLabels.kt` | 58 | FINDINGS | E | deep reviewer | review-reports/17-e-design-a11y-strings.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/editor/BlockListEditor.kt` | 329 | FINDINGS | B8 | full-lens reviewer | review-reports/08-question-editor.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/editor/EditorSession.kt` | 199 | FINDINGS | B8 | full-lens reviewer | review-reports/08-question-editor.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/editor/EditorViewModel.kt` | 1405 | FINDINGS | B8 | full-lens reviewer | review-reports/08-question-editor.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/editor/MathLiveEditor.kt` | 188 | FINDINGS | B8 | full-lens reviewer | review-reports/08-question-editor.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/editor/MathMlLatex.kt` | 173 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/editor/PassagesScreen.kt` | 603 | FINDINGS | B9 | full-lens reviewer | review-reports/09-passages.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/editor/QuestionEditorScreen.kt` | 912 | FINDINGS | B8 | full-lens reviewer | review-reports/08-question-editor.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/haptics/Haptics.kt` | 66 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/history/BookmarksScreen.kt` | 350 | FINDINGS | B13 | full-lens reviewer | review-reports/13-bookmarks.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/history/BookmarksViewModel.kt` | 166 | FINDINGS | B13 | full-lens reviewer | review-reports/13-bookmarks.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/history/HistoryScreen.kt` | 297 | FINDINGS | B11 | full-lens reviewer | review-reports/11-history.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/history/HistoryViewModel.kt` | 55 | FINDINGS | B11 | full-lens reviewer | review-reports/11-history.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/importscreen/ImportScreen.kt` | 672 | FINDINGS | B10 | full-lens reviewer | review-reports/10-import.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/importscreen/ImportViewModel.kt` | 974 | FINDINGS | B10 | full-lens reviewer | review-reports/10-import.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/library/BlueprintEditorSheet.kt` | 476 | FINDINGS | B1 | parallel reviewers | review-reports/01-library.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/library/LibraryDialogs.kt` | 267 | FINDINGS | B1 | parallel reviewers | review-reports/01-library.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/library/LibraryDrawer.kt` | 368 | FINDINGS | B1 | parallel reviewers | review-reports/01-library.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/library/LibraryPaperCard.kt` | 738 | FINDINGS | B1 | parallel reviewers | review-reports/01-library.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/library/LibraryScreen.kt` | 822 | FINDINGS | B1 | parallel reviewers | review-reports/01-library.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/library/LibraryViewModel.kt` | 955 | FINDINGS | B1 | parallel reviewers | review-reports/01-library.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/library/PickedArchive.kt` | 124 | FINDINGS | B1 | parallel reviewers | review-reports/01-library.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/library/TestSetupSheet.kt` | 1302 | FINDINGS | B1 | parallel reviewers | review-reports/01-library.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/navigation/McqNavHost.kt` | 270 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/navigation/Routes.kt` | 125 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/results/ResultsScreen.kt` | 1565 | FINDINGS | B4 | parallel reviewers | review-reports/04-results.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/results/ResultsViewModel.kt` | 281 | FINDINGS | B4 | parallel reviewers | review-reports/04-results.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/search/SearchScreen.kt` | 204 | FINDINGS | B14 | full-lens reviewer | review-reports/14-search.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/search/SearchViewModel.kt` | 97 | FINDINGS | B14 | full-lens reviewer | review-reports/14-search.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/sessions/SessionsScreen.kt` | 321 | FINDINGS | B12 | full-lens reviewer | review-reports/12-sessions.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/sessions/SessionsViewModel.kt` | 77 | FINDINGS | B12 | full-lens reviewer | review-reports/12-sessions.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/settings/AboutSection.kt` | 46 | FINDINGS | B7 | full-lens reviewer | review-reports/07-settings.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/settings/AppearanceSection.kt` | 354 | FINDINGS | B7 | full-lens reviewer | review-reports/07-settings.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/settings/BackupDataSection.kt` | 236 | FINDINGS | B7 | full-lens reviewer | review-reports/07-settings.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/settings/SettingsRows.kt` | 109 | FINDINGS | B7 | full-lens reviewer | review-reports/07-settings.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/settings/SettingsScreen.kt` | 366 | FINDINGS | B7 | full-lens reviewer | review-reports/07-settings.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/settings/SettingsSections.kt` | 52 | FINDINGS | B7 | full-lens reviewer | review-reports/07-settings.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/settings/SettingsViewModel.kt` | 882 | FINDINGS | B7 | full-lens reviewer | review-reports/07-settings.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/settings/StudySchedulerSection.kt` | 610 | FINDINGS | B7 | full-lens reviewer | review-reports/07-settings.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/settings/TestsSection.kt` | 89 | FINDINGS | B7 | full-lens reviewer | review-reports/07-settings.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/stats/CardInfoSheet.kt` | 186 | FINDINGS | B6 | full-lens reviewer | review-reports/06-stats.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/stats/StatsScreen.kt` | 261 | FINDINGS | B6 | full-lens reviewer | review-reports/06-stats.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/stats/StatsViewModel.kt` | 356 | FINDINGS | B6 | full-lens reviewer | review-reports/06-stats.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/stats/StatsWidgets.kt` | 211 | FINDINGS | B6 | full-lens reviewer | review-reports/06-stats.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/stats/charts/ChartGeometry.kt` | 55 | FINDINGS | B6 | full-lens reviewer | review-reports/06-stats.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/stats/charts/ChartKit.kt` | 265 | FINDINGS | B6 | full-lens reviewer | review-reports/06-stats.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/stats/charts/HeatmapChart.kt` | 133 | FINDINGS | B6 | full-lens reviewer | review-reports/06-stats.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/stats/charts/LineChart.kt` | 168 | FINDINGS | B6 | full-lens reviewer | review-reports/06-stats.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/stats/charts/MiniCharts.kt` | 115 | FINDINGS | B6 | full-lens reviewer | review-reports/06-stats.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/stats/charts/StackedBarChart.kt` | 242 | FINDINGS | B6 | full-lens reviewer | review-reports/06-stats.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/stats/sections/CardsSection.kt` | 330 | FINDINGS | B6 | full-lens reviewer | review-reports/06-stats.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/stats/sections/ExamSection.kt` | 429 | FINDINGS | B6 | full-lens reviewer | review-reports/06-stats.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/stats/sections/GuessingSection.kt` | 513 | FINDINGS | B6 | full-lens reviewer | review-reports/06-stats.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/stats/sections/MemorySection.kt` | 251 | FINDINGS | B6 | full-lens reviewer | review-reports/06-stats.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/stats/sections/OverviewSection.kt` | 288 | FINDINGS | B6 | full-lens reviewer | review-reports/06-stats.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/stats/sections/ProgressSection.kt` | 235 | FINDINGS | B6 | full-lens reviewer | review-reports/06-stats.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/stats/sections/RetentionSection.kt` | 579 | FINDINGS | B6 | full-lens reviewer | review-reports/06-stats.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/stats/sections/SessionsSection.kt` | 149 | FINDINGS | B6 | full-lens reviewer | review-reports/06-stats.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/stats/sections/TestsSection.kt` | 255 | FINDINGS | B6 | full-lens reviewer | review-reports/06-stats.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/stats/sections/WorkloadSection.kt` | 505 | FINDINGS | B6 | full-lens reviewer | review-reports/06-stats.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/study/StudyScreen.kt` | 1066 | FINDINGS | B2 | parallel reviewers | review-reports/02-study.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/study/StudyViewModel.kt` | 1400 | FINDINGS | B2 | parallel reviewers | review-reports/02-study.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/test/TestSessionScreen.kt` | 1280 | FINDINGS | B3 | parallel reviewers | review-reports/03-test-session.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/test/TestViewModel.kt` | 1469 | FINDINGS | B3 | parallel reviewers | review-reports/03-test-session.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/theme/AppThemes.kt` | 512 | FINDINGS | E | deep reviewer | review-reports/17-e-design-a11y-strings.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/theme/CustomTheme.kt` | 220 | FINDINGS | E | deep reviewer | review-reports/17-e-design-a11y-strings.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/theme/Theme.kt` | 61 | FINDINGS | E | deep reviewer | review-reports/17-e-design-a11y-strings.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/ui/theme/VerdictColors.kt` | 108 | FINDINGS | E | deep reviewer | review-reports/17-e-design-a11y-strings.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/util/BitmapDecoder.kt` | 60 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/util/BoundedRead.kt` | 149 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/util/ContentElements.kt` | 547 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/util/DelayedVisibility.kt` | 27 | FINDINGS | E | deep reviewer | review-reports/17-e-design-a11y-strings.md |
| Production Kotlin | `app/src/main/java/com/mcqapp/util/FontScale.kt` | 26 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/util/ImageUtils.kt` | 157 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/util/InlineHtml.kt` | 141 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/util/IntervalFormat.kt` | 59 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/util/Logger.kt` | 234 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/util/NumericInput.kt` | 54 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/util/QuestionImage.kt` | 104 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/util/ShareExport.kt` | 145 | PENDING | — | — | — |
| Production Kotlin | `app/src/main/java/com/mcqapp/util/SharedImport.kt` | 133 | PENDING | — | — | — |
| Resources | `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | 6 | PENDING | — | — | — |
| Resources | `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` | 6 | PENDING | — | — | — |
| Resources | `app/src/main/res/values/colors.xml` | 5 | PENDING | — | — | — |
| Resources | `app/src/main/res/values/strings.xml` | 1219 | FINDINGS | E | deep reviewer | review-reports/17-e-design-a11y-strings.md |
| Resources | `app/src/main/res/values/themes.xml` | 6 | FINDINGS | E | deep reviewer | review-reports/17-e-design-a11y-strings.md |
| Resources | `app/src/main/res/xml/backup_rules.xml` | 10 | PENDING | — | — | — |
| Resources | `app/src/main/res/xml/data_extraction_rules.xml` | 28 | PENDING | — | — | — |
| Resources | `app/src/main/res/xml/file_paths.xml` | 8 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/AnkiHtmlTest.kt` | 285 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/AnkiMediaPoolTest.kt` | 47 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/AnkiPackageReaderTest.kt` | 1111 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/AnkiPackageWriterTest.kt` | 680 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/AnkiRoundTripTest.kt` | 299 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/AnkiSchedulingTest.kt` | 473 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/AnkiSchema11JsonTest.kt` | 104 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/AnkiStreamEquivalenceTest.kt` | 490 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/AnkiTempFilesTest.kt` | 62 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/AnswerReviewTest.kt` | 90 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/AutoAdvanceTest.kt` | 21 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/BackupRestoreRoundTripTest.kt` | 744 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/BackupRestoreTest.kt` | 93 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/BackupRulesTest.kt` | 78 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/BackupSchedulingTest.kt` | 448 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/BackupStreamingReadTest.kt` | 292 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/BackupStreamingScaleTest.kt` | 136 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/BitmapDecoderTest.kt` | 108 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/BlueprintBugsDbTest.kt` | 182 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/BlueprintBugsTest.kt` | 428 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/BlueprintDbTest.kt` | 425 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/BlueprintEditorLogicTest.kt` | 45 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/BlueprintPreviewFreshTest.kt` | 337 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/BlueprintQuotasTest.kt` | 388 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/BlueprintSelectorTest.kt` | 347 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/BlueprintSetupScopeTest.kt` | 79 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/BlueprintTypeLabelTest.kt` | 33 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/BookmarkExportStreamTest.kt` | 184 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/BookmarkExportTest.kt` | 74 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/BookmarkRaceTest.kt` | 71 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/BookmarksPagingTest.kt` | 195 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/BoundedReadTest.kt` | 160 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/BrowseCursorTest.kt` | 165 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/BrowsePagingConfigTest.kt` | 43 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/BrowsePagingTest.kt` | 294 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/BrowseRowMoveStateTest.kt` | 111 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/BrowseWindowTest.kt` | 74 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/BulkOpsDbTest.kt` | 421 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/BulkOpsTest.kt` | 33 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/BulkQueryTest.kt` | 200 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/CalibrationTest.kt` | 863 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/CardContentTest.kt` | 303 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/CategoryCountsQueryTest.kt` | 245 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/CategoryCountsTest.kt` | 176 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/CategoryFilterTest.kt` | 41 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/CategoryFlatOrderTest.kt` | 133 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/CategorySelectionTest.kt` | 298 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ChunkShapeTest.kt` | 39 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ChunkedQueriesTest.kt` | 174 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ContentElementTest.kt` | 157 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ContentRenderProbeTest.kt` | 202 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/CopyQueryCountTest.kt` | 107 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/CorruptOptionsJsonTest.kt` | 189 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/CountdownWallClockTest.kt` | 56 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/CoverageDbTest.kt` | 92 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/CoverageStatsTest.kt` | 98 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/DayBoundaryTest.kt` | 205 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/DeferredDbQueriesTest.kt` | 233 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/DeletePaperCascadeTest.kt` | 356 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/DeletePaperConfirmationTest.kt` | 153 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/DeviceExportedFileTest.kt` | 143 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/DocxDbRoundTripTest.kt` | 88 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/DocxMultiAnswerTest.kt` | 157 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/DocxPaperWriterTest.kt` | 495 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/DocxParserTest.kt` | 478 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/DocxPassageTest.kt` | 144 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/DocxRoundTripTest.kt` | 745 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/DocxTrackedChangesTest.kt` | 147 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/DownscaledImagesTraversalTest.kt` | 115 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/DrillCountAvailabilityTest.kt` | 81 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/DrillTest.kt` | 31 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/DuplicateTableCollisionTest.kt` | 229 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/EditorBatchFixesTest.kt` | 252 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/EditorBugHuntProbeTest.kt` | 183 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/EditorMoveToQuestionTest.kt` | 401 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/EditorRegressionTest.kt` | 80 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/EditorSessionCursorTest.kt` | 151 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/EditorViewModelTest.kt` | 464 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/EditorWorkspaceRulesTest.kt` | 393 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/EntityDecodingAgreementTest.kt` | 92 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ExamModeTest.kt` | 32 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ExamStatsDbTest.kt` | 186 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ExamStatsTest.kt` | 388 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ExamTimingSessionSecondsTest.kt` | 107 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ExamTimingTest.kt` | 119 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ExifMatrixVectorsTest.kt` | 78 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ExportTestSupport.kt` | 114 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ExportWritersTest.kt` | 686 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ExposureInStudyDayTest.kt` | 127 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/FeedbackTest.kt` | 52 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/FontScaleTest.kt` | 25 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/Fsrs7SelectionTest.kt` | 334 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/Fsrs7UpgradeTest.kt` | 710 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/FsrsFitStorageGateTest.kt` | 84 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/FsrsImportGuardTest.kt` | 121 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/FsrsOptimizerTest.kt` | 821 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/FsrsReplayStreamTest.kt` | 124 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/FsrsReplayTest.kt` | 275 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/FsrsSchedulerTest.kt` | 709 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/FsrsTrainingLogStreamTest.kt` | 117 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/FsrsTunerPolicyTest.kt` | 168 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/GlobalSearchPrefilterTest.kt` | 211 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/GradeProvenanceTest.kt` | 96 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/GradingRulesStoreTest.kt` | 899 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/HangWatchdogRule.kt` | 100 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/HapticsTest.kt` | 76 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/HashCompatTest.kt` | 62 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/HistoryStatsParityTest.kt` | 119 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/HistoryViewModelRefreshTest.kt` | 117 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ImageDownscaleDecodeTest.kt` | 79 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ImageDownscaleTest.kt` | 57 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ImageUprightTest.kt` | 93 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ImportAnswerUpdateTest.kt` | 195 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ImportBackupRestoreTest.kt` | 150 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ImportButtonTest.kt` | 108 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ImportCollisionQueryCountTest.kt` | 192 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ImportFieldTextTest.kt` | 62 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ImportPaperIdentityTest.kt` | 504 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ImportPreviewClassificationTest.kt` | 212 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ImportPreviewScannerTest.kt` | 111 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ImportRowDiagnosticsTest.kt` | 51 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ImportRunJournalTest.kt` | 113 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ImportSchedulingTest.kt` | 244 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ImportSuggestionsTest.kt` | 63 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ImportWarningsTest.kt` | 119 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/InlineHtmlTest.kt` | 117 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/IntervalFormatTest.kt` | 100 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/JsonDepthGuardTest.kt` | 83 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/JsonPullSeparatorRecursionTest.kt` | 41 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/LatestStandingsTest.kt` | 167 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/LegacyParserLargeBankTest.kt` | 102 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/LegacyParserTest.kt` | 1066 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/LibraryDialogScrollTest.kt` | 40 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/LibraryStudyCountRefreshTest.kt` | 118 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/LibraryViewModelErrorTest.kt` | 114 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/LibraryViewModelStudyCountsTest.kt` | 160 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/LibraryViewModelUpdatePaperTest.kt` | 107 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/LoggerTest.kt` | 105 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/MarksTest.kt` | 31 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/MasteryTest.kt` | 73 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/MathHtmlTest.kt` | 250 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/MathLiveTest.kt` | 183 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/MathMlToOmmlTest.kt` | 194 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/McqBackupFormatTest.kt` | 273 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/MediaSpoolTest.kt` | 110 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/MigrationTest.kt` | 1787 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/MistakesTest.kt` | 100 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/NoDeadCodeTest.kt` | 329 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/OptionLetterTest.kt` | 34 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/PagingWalk.kt` | 36 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/PaperExporterSelectionTest.kt` | 197 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/PaperOrderTest.kt` | 79 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ParserFuzzTest.kt` | 73 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/PassageBlocksTest.kt` | 67 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/PassageJsonTest.kt` | 121 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/PassagePagingTest.kt` | 94 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/PassageReorderTest.kt` | 181 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/PassageRepositoryTest.kt` | 338 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/PdfAnswerKeyTest.kt` | 84 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/PdfColumnsTest.kt` | 27 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/PdfInlineSpanTest.kt` | 43 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/PdfMathTest.kt` | 231 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/PdfOversizedBlockTest.kt` | 92 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/PdfPaperWriterTest.kt` | 102 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/PickedArchiveTest.kt` | 290 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/PickedNameTest.kt` | 40 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ProgressStatsTest.kt` | 195 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/QuestionProgressLabelTest.kt` | 62 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/QuestionRefTextTest.kt` | 143 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/QuestionSearchTest.kt` | 309 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/QuestionSignalsTest.kt` | 280 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/QuestionStatsRecomputeTest.kt` | 372 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/QuestionStatsTest.kt` | 67 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ReadmeExamplesTest.kt` | 187 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/RecommendationEngineTest.kt` | 196 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ReliabilityTest.kt` | 280 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/RemoteImageGateTest.kt` | 102 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ReorderTest.kt` | 68 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ReportExportCsvTest.kt` | 111 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ReportPdfTest.kt` | 126 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/RepositorySettingsTest.kt` | 609 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/RepositoryTest.kt` | 1394 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/RestoreRebuildIsolationTest.kt` | 189 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ResultsPagingTest.kt` | 155 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ResultsViewModelTest.kt` | 145 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/RetentionAdditionsTest.kt` | 180 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ReviewFiltersTest.kt` | 58 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/RichTextSanitizingTest.kt` | 113 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/RobolectricMainThread.kt` | 89 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/RoutesTest.kt` | 192 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/SafeZipTest.kt` | 134 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/SampleFormattingTest.kt` | 169 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/SampleImageParseTest.kt` | 20 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/SampleImageRoundTripTest.kt` | 61 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/SamplePaperApkgRoundTripTest.kt` | 301 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/SampledBoundedTest.kt` | 183 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ScaledRowStorageTest.kt` | 167 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ScopeKeyTest.kt` | 116 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ScoringTest.kt` | 174 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/SelectionExportTest.kt` | 203 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/SendClassificationTest.kt` | 89 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/SessionClockTest.kt` | 46 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/SessionLoadFailureTest.kt` | 117 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/SessionOrderLoadTest.kt` | 164 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/SessionOrderTest.kt` | 189 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/SessionReviewPagingSourceTest.kt` | 86 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/SessionsTest.kt` | 406 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/SettingsExportDispatcherTest.kt` | 106 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/SettingsFormatValueTest.kt` | 32 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/SettingsResetRule.kt` | 83 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/SettingsSectionsTest.kt` | 90 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/SettingsViewModelResetTest.kt` | 264 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ShareExportTest.kt` | 154 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ShuffleTest.kt` | 158 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/SignalQueryTest.kt` | 393 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/SmallerAdditionsTest.kt` | 206 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/SpacedRepetitionTest.kt` | 828 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/StartupTest.kt` | 256 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/StatsAggregatorTest.kt` | 746 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/StatsCardDetailGuardTest.kt` | 128 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/StatsChartGeometryTest.kt` | 61 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/StatsChartKitTest.kt` | 141 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/StatsGuessingTest.kt` | 269 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/StatsOverviewStreamTest.kt` | 200 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/StatsPageStructureTest.kt` | 70 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/StatsRetentionStreamTest.kt` | 998 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/StatsScaleTest.kt` | 108 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/StatsSeedFailureTest.kt` | 226 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/StatsTabsStreamTest.kt` | 313 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/StorageInfoTest.kt` | 68 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/StoredQuestionHashTest.kt` | 227 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/StreamingImportCancelTest.kt` | 168 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/StreamingImportTest.kt` | 292 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/StreamingImporterEquivalenceTest.kt` | 525 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/StreamingImporterScaleTest.kt` | 102 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/StringResourceUsageTest.kt` | 195 | FINDINGS | E/G | deep reviewer | review-reports/17-e-design-a11y-strings.md; 19-g-tests-ci.md |
| Unit tests | `app/src/test/java/com/mcqapp/StudyActionStructureTest.kt` | 237 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/StudyCategoryScopeTest.kt` | 334 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/StudyCountsInputTest.kt` | 224 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/StudyDwellBandTest.kt` | 154 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/StudyPullQueryCountTest.kt` | 241 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/StudyQueueTest.kt` | 51 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/StudySessionStateTest.kt` | 53 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/StudySummaryTimesTest.kt` | 52 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/StudyViewModelPullTest.kt` | 931 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/StudyViewModelRestartFailureTest.kt` | 153 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/SubmissionTest.kt` | 50 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/SubmitSummaryTest.kt` | 45 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/TableCellRenderTest.kt` | 63 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/TableLayoutStructureTest.kt` | 33 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/TagNormalizerTest.kt` | 55 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/TestCategoryScopeTest.kt` | 153 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/TestDurationOverrideTest.kt` | 93 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/TestMcqApplication.kt` | 22 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/TestPlanSessionTest.kt` | 690 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/TestPlanTest.kt` | 574 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/TestProgressStoreTest.kt` | 340 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/TestProgressTest.kt` | 181 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/TestReportBuilderTest.kt` | 1165 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/TestSelectorTest.kt` | 624 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/TestSessionPrefetchTest.kt` | 185 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/TestSessionResumeTest.kt` | 416 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/TestSetupTest.kt` | 375 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/TestSnapshotTest.kt` | 489 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/TestTimingTest.kt` | 46 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ThemeSchemeTest.kt` | 331 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/TrendsTest.kt` | 116 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/UiAccessibilityTest.kt` | 90 | FINDINGS | E/G | deep reviewer | review-reports/17-e-design-a11y-strings.md; 19-g-tests-ci.md |
| Unit tests | `app/src/test/java/com/mcqapp/UpdateAnswersOnDuplicateTest.kt` | 206 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/VersionCodeGuardTest.kt` | 38 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/WebViewLifecycleTest.kt` | 124 | FINDINGS | E/G | deep reviewer | review-reports/17-e-design-a11y-strings.md; 19-g-tests-ci.md |
| Unit tests | `app/src/test/java/com/mcqapp/WorkloadAdditionsTest.kt` | 240 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/WrapperValidationTest.kt` | 51 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/XmlEscapingAppendableTest.kt` | 54 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/XxeHardeningTest.kt` | 69 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/ui/history/HistoryFormatTest.kt` | 22 | PENDING | — | — | — |
| Unit tests | `app/src/test/java/com/mcqapp/util/NumericInputTest.kt` | 78 | PENDING | — | — | — |
| Instrumentation tests | `app/src/androidTest/java/com/mcqapp/BlueprintPathsUiTest.kt` | 202 | PENDING | — | — | — |
| Instrumentation tests | `app/src/androidTest/java/com/mcqapp/BrowseCategoryMoveUiTest.kt` | 129 | PENDING | — | — | — |
| Instrumentation tests | `app/src/androidTest/java/com/mcqapp/BrowseDeepLinkUiTest.kt` | 167 | PENDING | — | — | — |
| Instrumentation tests | `app/src/androidTest/java/com/mcqapp/BrowseWindowUiTest.kt` | 133 | PENDING | — | — | — |
| Instrumentation tests | `app/src/androidTest/java/com/mcqapp/CriticalPathTest.kt` | 170 | PENDING | — | — | — |
| Instrumentation tests | `app/src/androidTest/java/com/mcqapp/CustomTestPathsUiTest.kt` | 219 | PENDING | — | — | — |
| Instrumentation tests | `app/src/androidTest/java/com/mcqapp/EditorSaveButtonUiTest.kt` | 166 | PENDING | — | — | — |
| Instrumentation tests | `app/src/androidTest/java/com/mcqapp/PassageMemberDeepLinkUiTest.kt` | 118 | PENDING | — | — | — |
| Instrumentation tests | `app/src/androidTest/java/com/mcqapp/PassagesUiTest.kt` | 99 | PENDING | — | — | — |
| Instrumentation tests | `app/src/androidTest/java/com/mcqapp/QuestionEditorUiTest.kt` | 382 | PENDING | — | — | — |
| Instrumentation tests | `app/src/androidTest/java/com/mcqapp/ResultsPracticeUiTest.kt` | 154 | PENDING | — | — | — |
| Instrumentation tests | `app/src/androidTest/java/com/mcqapp/SampleImageUiTest.kt` | 70 | PENDING | — | — | — |
| Instrumentation tests | `app/src/androidTest/java/com/mcqapp/SettingsUiTest.kt` | 129 | PENDING | — | — | — |
| Instrumentation tests | `app/src/androidTest/java/com/mcqapp/ShareExportUiTest.kt` | 103 | PENDING | — | — | — |
| Instrumentation tests | `app/src/androidTest/java/com/mcqapp/StudyAndSelectionUiTest.kt` | 1064 | PENDING | — | — | — |
| Config / schema | `AGENTS.md` | 126 | PENDING | — | — | — |
| Config / schema | `PROJECT_NOTES.md` | 312 | PENDING | — | — | — |
| Config / schema | `app/build.gradle.kts` | 289 | FINDINGS | G | deep reviewer | review-reports/19-g-tests-ci.md |
| Config / schema | `build.gradle.kts` | 6 | PENDING | — | — | — |
| Config / schema | `settings.gradle.kts` | 17 | PENDING | — | — | — |
| Config / schema | `gradle.properties` | 6 | PENDING | — | — | — |
| Config / schema | `.github/workflows/build.yml` | 216 | FINDINGS | G | deep reviewer | review-reports/19-g-tests-ci.md |
| Config / schema | `.github/workflows/release.yml` | 130 | FINDINGS | G | deep reviewer | review-reports/19-g-tests-ci.md |
| Config / schema | `app/src/main/AndroidManifest.xml` | 45 | PENDING | — | — | — |
| Config / schema | `app/schemas/com.mcqapp.data.local.AppDatabase/28.json` | 1832 | FINDINGS | D | deep reviewer | review-reports/16-d-data-integrity.md |
