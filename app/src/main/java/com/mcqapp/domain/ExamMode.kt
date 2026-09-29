package com.mcqapp.domain

/**
 * Visibility matrix for test aids. Strict exam mode hides reveal, flags
 * and the question palette; it also suppresses practice-mode live
 * feedback (the two modes contradict, strict wins).
 */
object ExamMode {

    fun canReveal(practiceMode: Boolean, strictMode: Boolean): Boolean =
        !practiceMode && !strictMode

    fun canFlag(strictMode: Boolean): Boolean = !strictMode

    fun canOpenPalette(strictMode: Boolean): Boolean = !strictMode

    fun effectivePractice(practiceMode: Boolean, strictMode: Boolean): Boolean =
        practiceMode && !strictMode
}
