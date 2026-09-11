package com.smartview.glassai.debug

import com.smartview.glassai.glasses.DisplayCard
import com.smartview.glassai.glasses.DisplayStrings
import com.smartview.glassai.glasses.LeanEatFood
import com.smartview.glassai.glasses.LiveAIPhase
import java.util.Base64

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
        "WeChat" to DisplayCard.WeChat(
            "Weekend walk", "Alex: I’m at the café by the station. See you in ten minutes!\n" +
                "小明：我带了水和水果，我们沿河边走到老桥，再去公园休息。😀\n" +
                "Sam: The shaded entrance is open. There are benches beside the river; " +
                "meet there after coffee and we can decide how far to walk.", count = 3, timestamp = 1_789_142_400_000L,
        ),
        "Music" to DisplayCard.Music("Morning Light", "The Weekend Sessions", isPlaying = true,
            app = "Sample player", artJpeg = sampleArt),
    )

    // A generated 16×16 blue/gold fixture, not downloaded album art or a real user's media.
    private val sampleArt: ByteArray by lazy { Base64.getDecoder().decode(
        "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBT/wAARCAAQABADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD52/Zo/Zo/4aJ/4SP/AIqP/hH/AOx/s3/Lj9p87zfN/wCmibceV753dscn7S/7NH/DO3/COf8AFR/8JB/bH2n/AJcfs3k+V5X/AE0fdnzfbG3vng/Zo/aX/wCGdv8AhI/+Kc/4SD+2Ps3/AC/fZvJ8rzf+mb7s+b7Y2988H7S/7S//AA0T/wAI5/xTn/CP/wBj/af+X77T53m+V/0zTbjyvfO7tjnq/wCM6/16/wCpP/3B/wCfP/g7+N/XKdf/AAn/ANn/APT/AP7e/m/8B+H+rn//2Q==",
    ) }
}
