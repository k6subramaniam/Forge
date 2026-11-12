package com.forge.core.grocery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Grocery list building (blueprint 3.13): aggregation across recipes,
 * servings scaling, pantry exclusion with an audit trail, unquantified
 * ingredients surfaced instead of dropped, and aisle-walking order.
 */
class GroceryListTest {

    private fun recipe(
        name: String,
        base: Int = 2,
        target: Int = 2,
        vararg ingredients: RecipeIngredient,
    ) = PlannedRecipe(name, base, target, ingredients.toList())

    private fun ing(name: String, grams: Double?, aisle: GroceryAisle = GroceryAisle.OTHER) =
        RecipeIngredient(name, grams, aisle)

    @Test
    fun `same ingredient across recipes aggregates into one line`() {
        val list = GroceryListBuilder.build(
            listOf(
                recipe("Grilled chicken", ingredients = arrayOf(ing("Chicken breast", 300.0, GroceryAisle.MEAT_AND_SEAFOOD))),
                recipe("Chicken soup", ingredients = arrayOf(ing("chicken breast", 200.0, GroceryAisle.MEAT_AND_SEAFOOD))),
            ),
        )
        assertEquals(1, list.items.size)
        val item = list.items.single()
        assertEquals(500.0, item.totalGrams, 0.001)
        assertEquals(listOf("Grilled chicken", "Chicken soup"), item.usedIn)
    }

    @Test
    fun `quantities scale from base to target servings`() {
        val list = GroceryListBuilder.build(
            listOf(
                recipe(
                    "Rice bowl", base = 2, target = 5,
                    ingredients = arrayOf(ing("Rice", 180.0, GroceryAisle.PANTRY)),
                ),
            ),
        )
        assertEquals(450.0, list.items.single().totalGrams, 0.001)
    }

    @Test
    fun `pantry staples are excluded and recorded, case and plural insensitively`() {
        val list = GroceryListBuilder.build(
            listOf(
                recipe(
                    "Stir fry",
                    ingredients = arrayOf(
                        ing("Olive Oils", 30.0, GroceryAisle.PANTRY),
                        ing("Broccoli", 200.0, GroceryAisle.PRODUCE),
                    ),
                ),
            ),
            pantryStaples = setOf("olive oil"),
        )
        assertEquals(listOf("Broccoli"), list.items.map { it.name })
        assertEquals(listOf("Olive Oils"), list.excludedPantry)
    }

    @Test
    fun `common produce plurals aggregate and match staples`() {
        val list = GroceryListBuilder.build(
            listOf(
                recipe(
                    "Salad",
                    ingredients = arrayOf(
                        ing("Tomato", 100.0, GroceryAisle.PRODUCE),
                        ing("Berries", 80.0, GroceryAisle.PRODUCE),
                        ing("Peach", 150.0, GroceryAisle.PRODUCE),
                    ),
                ),
                recipe(
                    "Smoothie",
                    ingredients = arrayOf(
                        ing("Tomatoes", 50.0, GroceryAisle.PRODUCE),
                        ing("berry", 40.0, GroceryAisle.PRODUCE),
                        ing("Peaches", 150.0, GroceryAisle.PRODUCE),
                    ),
                ),
            ),
        )
        // One line per food, named as first seen, quantities summed.
        assertEquals(
            mapOf("Berries" to 120.0, "Peach" to 300.0, "Tomato" to 150.0),
            list.items.associate { it.name to it.totalGrams },
        )

        // A stocked "tomato" covers a recipe's "tomatoes".
        val stocked = GroceryListBuilder.build(
            listOf(recipe("Sauce", ingredients = arrayOf(ing("Tomatoes", 400.0, GroceryAisle.PRODUCE)))),
            pantryStaples = setOf("tomato"),
        )
        assertTrue(stocked.items.isEmpty())
        assertEquals(listOf("Tomatoes"), stocked.excludedPantry)
    }

    @Test
    fun `ingredient without quantity is surfaced as unquantified, never dropped silently`() {
        val list = GroceryListBuilder.build(
            listOf(
                recipe(
                    "Salad",
                    ingredients = arrayOf(
                        ing("Fresh herbs", null, GroceryAisle.PRODUCE),
                        ing("Lettuce", 100.0, GroceryAisle.PRODUCE),
                    ),
                ),
            ),
        )
        assertEquals(listOf("Lettuce"), list.items.map { it.name })
        assertEquals(listOf("Fresh herbs"), list.unquantified)
    }

    @Test
    fun `items group in store-walking order, alphabetical within an aisle`() {
        val list = GroceryListBuilder.build(
            listOf(
                recipe(
                    "Mixed plan",
                    ingredients = arrayOf(
                        ing("Milk", 500.0, GroceryAisle.DAIRY_AND_EGGS),
                        ing("Zucchini", 150.0, GroceryAisle.PRODUCE),
                        ing("Apples", 400.0, GroceryAisle.PRODUCE),
                        ing("Salmon", 250.0, GroceryAisle.MEAT_AND_SEAFOOD),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf(GroceryAisle.PRODUCE, GroceryAisle.MEAT_AND_SEAFOOD, GroceryAisle.DAIRY_AND_EGGS),
            list.itemsByAisle().map { it.first },
        )
        assertEquals(listOf("Apples", "Zucchini"), list.itemsByAisle().first().second.map { it.name })
    }

    @Test
    fun `empty plan builds an empty list`() {
        val list = GroceryListBuilder.build(emptyList())
        assertTrue(list.items.isEmpty() && list.excludedPantry.isEmpty() && list.unquantified.isEmpty())
        assertTrue(list.itemsByAisle().isEmpty())
    }

    @Test
    fun `suggested purchase rounds up to a 50 gram step with a 50 gram floor`() {
        assertEquals(50.0, GroceryItem("Salt", 10.0, GroceryAisle.PANTRY, emptyList()).suggestedPurchaseGrams, 0.001)
        assertEquals(500.0, GroceryItem("Flour", 470.0, GroceryAisle.PANTRY, emptyList()).suggestedPurchaseGrams, 0.001)
        assertEquals(500.0, GroceryItem("Flour", 500.0, GroceryAisle.PANTRY, emptyList()).suggestedPurchaseGrams, 0.001)
    }

    @Test
    fun `withChecked returns a checked copy without mutating the original`() {
        val list = GroceryListBuilder.build(
            listOf(recipe("Plan", ingredients = arrayOf(ing("Eggs", 120.0, GroceryAisle.DAIRY_AND_EGGS)))),
        )
        val checked = list.withChecked(setOf("eggs"))
        assertTrue(checked.items.single().checked)
        assertFalse(list.items.single().checked)
    }

    @Test
    fun `non-positive servings and quantities are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlannedRecipe("Bad", baseServings = 0, targetServings = 2, ingredients = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlannedRecipe("Bad", baseServings = 2, targetServings = 0, ingredients = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) { ing("Nothing", 0.0) }
    }
}
