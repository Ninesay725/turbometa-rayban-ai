package com.smartview.glassai.glasses

import com.smartview.glassai.models.FoodItem
import com.smartview.glassai.models.FoodNutritionResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class LeanEatCardMapperTest {
    @Test fun mapsTotalsFoodsAndSuggestionsWithWholeNumbers() {
        val response = FoodNutritionResponse(
            foods = listOf(
                FoodItem("Rice", "1 bowl", 200, 4.5, 0.5, 40.0),
                FoodItem("Egg", "1 egg", 80, 6.2, 5.1, 0.4),
                FoodItem("Salad", "1 plate", 20, 2.0, 1.3, 4.8),
            ),
            totalCalories = 300, totalProtein = 12.7, totalFat = 6.9, totalCarbs = 45.2,
            healthScore = 83, suggestions = listOf("More vegetables", "少放盐"),
        )
        assertEquals(
            DisplayCard.LeanEat(
                300, 12, 6, 45, 83,
                listOf(LeanEatFood("Rice", "1 bowl", 200), LeanEatFood("Egg", "1 egg", 80), LeanEatFood("Salad", "1 plate", 20)),
                listOf("More vegetables", "少放盐"),
            ),
            response.toLeanEatCard(),
        )
    }

    @Test fun emptyResponseMapsToAnEmptyCard() {
        val card = FoodNutritionResponse().toLeanEatCard()
        assertEquals(DisplayCard.LeanEat(0, 0, 0, 0, 0, emptyList(), emptyList()), card)
        assertEquals(1, card.pageCount())
    }

    @Test fun preservesTheRequestedPageForNodeClamping() {
        assertEquals(7, FoodNutritionResponse().toLeanEatCard(page = 7).page)
        assertEquals(-1, FoodNutritionResponse().toLeanEatCard(page = -1).page)
    }
}
