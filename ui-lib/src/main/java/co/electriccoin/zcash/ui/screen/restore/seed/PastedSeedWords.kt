package co.electriccoin.zcash.ui.screen.restore.seed

import java.util.Locale

/**
 * Break pasted text into candidate seed words: whitespace, commas and semicolons all separate
 * words, and tokens with no letters (the "1." or "12)" of a numbered backup) are dropped. BIP-39
 * words are lowercase, so the case of the paste is not preserved.
 */
internal fun splitPastedSeedWords(text: String): List<String> =
    text
        .split(PASTED_SEED_SEPARATOR)
        .map { it.trim().lowercase(Locale.ROOT) }
        .filter { token -> token.any { it.isLetter() } }

/**
 * Lay [words] over [current], starting at [index]; a paste that holds a whole phrase always starts
 * at the first field so pasting it into any box fills the grid. Words that would spill past the
 * last field are dropped.
 */
internal fun placePastedSeedWords(
    current: List<String>,
    index: Int,
    words: List<String>
): List<String> {
    val start = if (words.size >= current.size) 0 else index.coerceIn(0, current.lastIndex)
    val result = current.toMutableList()
    words.take(current.size - start).forEachIndexed { offset, word -> result[start + offset] = word }
    return result.toList()
}

private val PASTED_SEED_SEPARATOR = Regex("[\\s,;]+")
