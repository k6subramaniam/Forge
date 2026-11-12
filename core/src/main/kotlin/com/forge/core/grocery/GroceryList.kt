package com.forge.core.grocery

import kotlin.math.ceil
import com.forge.core.text.singular

/**
 * Grocery list building (blueprint 3.13): meal plans and recipes become one
 * aggregated, aisle-grouped shopping list.
 *
 * The rules here are deliberately the same honesty rules as the rest of :core:
 * - Quantities aggregate across meals (three recipes using chicken produce one
 *   combined line), scaled from each recipe's base servings to the target
 *   servings.
 * - Pantry staples the user already stocks are excluded, and every exclusion
 *   is recorded on the result so the review screen can show what was skipped
 *   and let the user put it back.
 * - An ingredient without a quantity cannot be aggregated into a purchase
 *   amount, so it is surfaced in [GroceryList.unquantified] rather than
 *   silently dropped or guessed at.
 * - Aisles follow store-walking order (blueprint 3.13) so the in-store
 *   check-off flow walks the store once.
 */

/** Store aisles in the order the list presents them (blueprint 3.13). */
enum class GroceryAisle {
    PRODUCE,
    MEAT_AND_SEAFOOD,
    DAIRY_AND_EGGS,
    BAKERY,
    FROZEN,
    PANTRY,
    OTHER,
}

/**
 * One ingredient of a recipe, with the quantity for the recipe's base
 * servings. [quantityGrams] is null when the recipe does not state a
 * quantity — see the file-level note on [GroceryList.unquantified].
 */
data class RecipeIngredient(
    val name: String,
    val quantityGrams: Double? = null,
    val aisle: GroceryAisle = GroceryAisle.OTHER,
) {
    init {
        require(name.isNotBlank()) { "Ingredient name must not be blank" }
        require(quantityGrams == null || quantityGrams > 0.0) {
            "Ingredient quantity must be positive when stated: $name"
        }
    }
}

/**
 * A recipe (or one meal of a plan) scheduled for [targetServings] servings.
 * Quantities in [ingredients] are written for [baseServings] servings and are
 * scaled by target/base when the list is built.
 */
data class PlannedRecipe(
    val name: String,
    val baseServings: Int,
    val targetServings: Int,
    val ingredients: List<RecipeIngredient>,
) {
    init {
        require(name.isNotBlank()) { "Recipe name must not be blank" }
        require(baseServings > 0) { "baseServings must be positive for $name" }
        require(targetServings > 0) { "targetServings must be positive for $name" }
    }

    /** Servings multiplier applied to every stated quantity. */
    val scaleFactor: Double get() = targetServings.toDouble() / baseServings.toDouble()
}

/**
 * One aggregated shopping line: everything the plan needs of one ingredient,
 * summed and scaled. [totalGrams] is the exact scaled total;
 * [suggestedPurchaseGrams] rounds it up to a sensible purchase unit (the
 * smallest 50 g step that covers the total, at least 50 g) — a core-side
 * deterministic rule the app layer may refine with real package sizes later.
 * [usedIn] names the recipes that contributed, for the review screen.
 */
data class GroceryItem(
    val name: String,
    val totalGrams: Double,
    val aisle: GroceryAisle,
    val usedIn: List<String>,
    val checked: Boolean = false,
) {
    val suggestedPurchaseGrams: Double
        get() = maxOf(50.0, ceil(totalGrams / 50.0) * 50.0)
}

/**
 * The built list. [items] is in aisle-walking order with alphabetical order
 * inside each aisle. [excludedPantry] holds the display names of ingredients
 * left out because they are marked as stocked pantry staples.
 * [unquantified] holds the display names of ingredients that stated no
 * quantity and so could not be totalled — they need a human decision on the
 * review screen, never a silent guess.
 */
data class GroceryList(
    val items: List<GroceryItem>,
    val excludedPantry: List<String>,
    val unquantified: List<String>,
) {
    /** Non-empty aisle groups in store-walking order, for the shopping-mode walk. */
    fun itemsByAisle(): List<Pair<GroceryAisle, List<GroceryItem>>> =
        GroceryAisle.entries.mapNotNull { aisle ->
            items.filter { it.aisle == aisle }.takeIf { it.isNotEmpty() }?.let { aisle to it }
        }

    /** A copy with the named lines checked/unchecked for shopping mode (core stays immutable). */
    fun withChecked(checkedNames: Set<String>): GroceryList {
        val wanted = checkedNames.map { GroceryListBuilder.normalize(it) }.toSet()
        return copy(items = items.map { it.copy(checked = GroceryListBuilder.normalize(it.name) in wanted) })
    }
}

/**
 * Builds a [GroceryList] from planned recipes.
 *
 * [pantryStaples] are the ingredients the user already stocks (oils, spices,
 * rice, …). Matching is normalized — case-insensitive, whitespace-collapsed,
 * and insensitive to a trailing plural "s" on the last word — so "Olive Oils"
 * in a recipe matches the staple "olive oil". Aggregation keys on the same
 * normalized name; the display name and aisle come from the first occurrence.
 */
object GroceryListBuilder {

    fun build(recipes: List<PlannedRecipe>, pantryStaples: Set<String> = emptySet()): GroceryList {
        val staples = pantryStaples.map(::normalize).toSet()
        val totals = linkedMapOf<String, Accumulator>()
        val excluded = mutableListOf<String>()
        val unquantified = mutableListOf<String>()

        for (recipe in recipes) {
            for (ingredient in recipe.ingredients) {
                val key = normalize(ingredient.name)
                if (key in staples) {
                    if (ingredient.name !in excluded) excluded += ingredient.name
                    continue
                }
                val grams = ingredient.quantityGrams
                if (grams == null) {
                    if (ingredient.name !in unquantified) unquantified += ingredient.name
                    continue
                }
                val acc = totals.getOrPut(key) {
                    Accumulator(displayName = ingredient.name, aisle = ingredient.aisle)
                }
                acc.totalGrams += grams * recipe.scaleFactor
                if (recipe.name !in acc.usedIn) acc.usedIn += recipe.name
            }
        }

        val items = totals.values
            .map { GroceryItem(it.displayName, it.totalGrams, it.aisle, it.usedIn.toList()) }
            .sortedWith(compareBy<GroceryItem> { it.aisle.ordinal }.thenBy { it.name.lowercase() })
        return GroceryList(items, excluded, unquantified)
    }

    /**
     * Lowercase, trim, collapse whitespace, and singularize the last word with
     * the same plural rules the generation validator uses ("tomatoes" →
     * "tomato", "berries" → "berry"), so both forms land on one line.
     */
    internal fun normalize(name: String): String {
        val collapsed = name.trim().lowercase().replace(Regex("\\s+"), " ")
        val lastSpace = collapsed.lastIndexOf(' ')
        val head = if (lastSpace < 0) "" else collapsed.substring(0, lastSpace + 1)
        val last = if (lastSpace < 0) collapsed else collapsed.substring(lastSpace + 1)
        return head + singular(last)
    }

    private class Accumulator(val displayName: String, val aisle: GroceryAisle) {
        var totalGrams: Double = 0.0
        val usedIn = mutableListOf<String>()
    }
}
