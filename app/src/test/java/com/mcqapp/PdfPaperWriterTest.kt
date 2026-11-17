package com.mcqapp

/**
 * The PDF byte assertions formerly here (producesARealPdfFile,
 * anEmptyPaperStillProducesAValidFile, answersAtEndKeepsTheBodyAndTheKeyLayout,
 * aTwoColumnExportStillProducesAValidFile, largePapersArePaginatedRatherThanDropped)
 * were class-level @Ignore because Robolectric's PdfDocument is already closed
 * on the JVM host. They now run on the emulator as PdfPaperWriterDeviceTest in
 * app/src/androidTest — CI's connectedDebugAndroidTest job executes them, so
 * this suite no longer reports them as skipped coverage.
 */
