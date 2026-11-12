package com.forge.core.text

/**
 * Simple English plural normalization, so a food's singular and plural
 * forms meet as one word: -ies → -y ("berries" → "berry"), -es after a
 * sibilant or -o ("peaches" → "peach", "tomatoes" → "tomato"), and a plain
 * trailing -s ("peanuts" → "peanut"). Words ending in -ss or -us are left
 * alone ("glass", "hummus"), as are words of three letters or fewer.
 *
 * Shared by the generation validator (allergen and dislike matching) and
 * the grocery list (aggregation and pantry staples), so both read food
 * names the same way.
 */
internal fun singular(word: String): String = when {
    word.length > 3 && word.endsWith("ies") ->
        word.dropLast(3) + "y"
    word.length > 4 && (
        word.endsWith("ches") || word.endsWith("shes") || word.endsWith("sses") ||
            word.endsWith("xes") || word.endsWith("zes") || word.endsWith("oes")
        ) ->
        word.dropLast(2)
    word.length > 3 && word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") ->
        word.dropLast(1)
    else -> word
}
