package com.mcqapp.data.export

import com.mcqapp.data.io.OptionDto

/**
 * Spreadsheet-style option labels: A-Z, then AA-AZ, BA-BZ (bijective base-26).
 *
 * An option is named by the position it is printed at, never by its stored id:
 * ids come from whatever produced the bank and can be anything, and an answer
 * only helps if it names the label the reader can see next to the option.
 *
 * The Anki reader recognises this same shape when it reads a deck back.
 */
internal fun optionLetter(index: Int): String {
    var n = index
    val sb = StringBuilder()
    do {
        sb.append('A' + n % 26)
        n = n / 26 - 1
    } while (n >= 0)
    return sb.reverse().toString()
}

/**
 * The correct options' labels, in the order the options are printed: "B", or
 * "B, D" for a multi-answer question.
 *
 * The answer names labels, never option content: a reader checking their work
 * wants "B", and repeating the content only makes the answer line a second copy
 * of an option they can already see.
 */
internal fun answerLabels(options: List<OptionDto>, correctOptionIds: List<String>): List<String> =
    options.mapIndexedNotNull { index, option ->
        if (option.id in correctOptionIds) optionLetter(index) else null
    }
