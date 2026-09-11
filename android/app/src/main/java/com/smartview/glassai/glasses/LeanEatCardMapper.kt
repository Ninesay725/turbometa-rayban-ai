package com.smartview.glassai.glasses

import com.smartview.glassai.models.FoodNutritionResponse

/** Read only nutrition data; the model's Compose color and rating getters are not used. */
fun FoodNutritionResponse.toLeanEatCard(page: Int = 0): DisplayCard.LeanEat = DisplayCard.LeanEat(
    totalCalories = totalCalories,
    totalProtein = totalProtein.toInt(),
    totalFat = totalFat.toInt(),
    totalCarbs = totalCarbs.toInt(),
    healthScore = healthScore,
    foods = foods.map { LeanEatFood(it.name, it.portion, it.calories) },
    suggestions = suggestions,
    page = page,
)
