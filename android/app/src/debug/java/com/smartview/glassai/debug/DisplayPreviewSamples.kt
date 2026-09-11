package com.smartview.glassai.debug

import com.smartview.glassai.glasses.DisplayCard
import com.smartview.glassai.glasses.DisplayStrings
import com.smartview.glassai.glasses.LeanEatFood
import com.smartview.glassai.glasses.LiveAIPhase

/** Literal fixture content; labels and card controls use the supplied localized display strings. */
object DisplayPreviewSamples {
    fun all(strings: DisplayStrings): List<Pair<String, DisplayCard>> = listOf(
        "Ray-Ban Display" to DisplayCard.Status("Ray-Ban Display", liveAiReady = true, openClawConnected = true),
        strings.looking to DisplayCard.Notice(strings.quickVision, strings.looking),
        strings.liveAi to DisplayCard.LiveAI(
            phase = LiveAIPhase.SPEAKING,
            userText = "What can I make with these ingredients?",
            assistantText = "Try a rice bowl with grilled salmon, broccoli and a squeeze of lemon. " +
                "Steam the broccoli while the rice cooks, then serve everything with a little soy sauce.",
            isFinal = false,
        ),
        strings.quickVision to DisplayCard.QuickVision(
            modeName = strings.quickVision,
            resultText = (
                "A sunlit neighborhood café has a chalkboard menu beside the entrance. " +
                    "Two bicycles are parked by the window, and a small dog rests beneath a table. " +
                    "The counter offers bread, fruit and coffee. There is an accessible entrance " +
                    "to the left of the steps, marked by a blue sign. A shaded bench sits nearby. "
                ).repeat(3),
        ),
        strings.leanEat to DisplayCard.LeanEat(
            totalCalories = 620, totalProtein = 38, totalFat = 20, totalCarbs = 72, healthScore = 86,
            foods = listOf(
                LeanEatFood("Grilled salmon", "150 g", 280),
                LeanEatFood("Brown rice", "1 bowl", 240),
                LeanEatFood("Steamed broccoli", "1 cup", 100),
            ),
            suggestions = listOf("Add a piece of fruit for fiber.", "Serve the sauce on the side."),
        ),
        strings.openClaw to DisplayCard.OpenClaw(
            userText = "Help me organize a weekend walk.",
            replyText = (
                "Start at the riverside park and follow the shaded path toward the old bridge. " +
                    "The first section is flat and has benches for a short break. " +
                    "Bring water and comfortable shoes, and check the forecast before leaving. "
                ).repeat(2),
            isFinal = true,
        ),
        "WeChat" to DisplayCard.WeChat("Alex", "I’m at the café by the station. See you in ten minutes!"),
        "Music" to DisplayCard.Music("Morning Light", "The Weekend Sessions", isPlaying = true),
    )
}
