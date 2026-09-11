package com.smartview.glassai.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BridgeModelsTest {
    @Test
    fun inboxKeepsOnlyTheLatestThreeAcrossUpdates() {
        val inbox = WeChatInbox()
        val oldest = message("old", 10)
        val second = message("second", 20)
        val third = message("third", 30)
        val newest = message("new", 40)

        inbox.update(listOf(third, oldest, second))
        assertEquals(listOf(newest, third, second), inbox.update(listOf(newest)))
        assertEquals(listOf(newest, third, second), inbox.messages)
    }

    @Test
    fun cumulativePostedUpdatesDoNotDuplicateMessagesOrDisplaceOtherKeys() {
        val inbox = WeChatInbox()
        val first = message("chat", 10, "First")
        val second = message("chat", 20, "Second")
        val other = message("other-chat", 30)
        inbox.update(listOf(first))
        inbox.update(listOf(other))

        assertEquals(listOf(other, second, first), inbox.update(listOf(first, second, second)))
        assertEquals(listOf(other, second, first), inbox.update(listOf(first, second)))
    }

    @Test
    fun messageIdentityIncludesKeySenderTextAndTimestamp() {
        val base = message("chat", 10, "Again")
        val differentMessages = listOf(
            base.copy(notificationKey = "another-chat"),
            base.copy(sender = "Another sender"),
            base.copy(text = "Different"),
            base.copy(timestamp = 20),
        )

        differentMessages.forEach { different ->
            val inbox = WeChatInbox()
            val result = inbox.update(listOf(base, base.copy(), different))
            assertEquals(setOf(base, different), result.toSet())
            assertEquals(2, result.size)
        }
    }

    @Test
    fun removalDeletesEveryMessageForTheOldKeyAndDoesNotRestoreEvictedContent() {
        val inbox = WeChatInbox()
        val other = message("current", 40)
        inbox.update(listOf(message("evicted", 10), message("old", 20), message("old", 30), other))

        assertEquals(listOf(other), inbox.remove("old"))
        assertEquals(listOf(other), inbox.remove("unknown"))
        assertEquals(emptyList<BridgeMessage>(), inbox.remove("current"))
        assertEquals(emptyList<BridgeMessage>(), inbox.messages)
    }

    @Test
    fun removingOldKeyBeforeAnEmptyUpdatePreservesOtherKeyAndLaterReadableContentReturns() {
        val inbox = WeChatInbox()
        val other = message("other", 30)
        inbox.update(listOf(message("old", 10, "Private detail"), message("old", 20), other))

        inbox.remove("old")
        assertEquals(listOf(other), inbox.update(emptyList()))

        val readable = message("old", 40, "New readable preview")
        inbox.remove("old")
        assertEquals(listOf(readable, other), inbox.update(listOf(readable)))
    }

    @Test
    fun clearDropsAllRetainedPreviews() {
        val inbox = WeChatInbox()
        inbox.update(listOf(message("old", 20)))
        inbox.clear()

        assertEquals(emptyList<BridgeMessage>(), inbox.messages)
        val fresh = message("fresh", 30)
        assertEquals(listOf(fresh), inbox.update(listOf(fresh)))
    }

    @Test
    fun emptyUpdatePreservesInboxAndInputMutationCannotChangeIt() {
        val inbox = WeChatInbox()
        val preview = message("chat", 10)
        val input = mutableListOf(preview)
        inbox.update(input)
        input.clear()

        assertEquals(listOf(preview), inbox.update(emptyList()))
    }

    @Test
    fun selectionFiltersPackagesBeforeConsideringPlayback() {
        val allowed = media("allowed", "com.netease.cloudmusic", playing = false, changedAt = 10)
        val forbidden = media("forbidden", "other.player", playing = true, changedAt = 100)

        assertSame(allowed, selectMedia(listOf(forbidden, allowed), setOf("com.netease.cloudmusic")))
        assertNull(selectMedia(listOf(forbidden), setOf("com.netease.cloudmusic")))
    }

    @Test
    fun playingSessionWinsOverMoreRecentlyChangedPausedSession() {
        val playing = media("playing", playing = true, changedAt = 10)
        val paused = media("paused", playing = false, changedAt = 100)

        assertSame(playing, selectMedia(listOf(paused, playing), setOf("com.luna.music")))
    }

    @Test
    fun mostRecentPlaybackChangeWinsWithinTheSamePlaybackState() {
        listOf(false, true).forEach { playing ->
            val older = media("older", playing = playing, changedAt = 10)
            val newer = media("newer", playing = playing, changedAt = 20)

            assertSame(newer, selectMedia(listOf(older, newer), setOf("com.luna.music")))
        }
    }

    @Test
    fun tiedSessionsHaveAStablePackageThenIdFallbackRegardlessOfInputOrder() {
        val winner = media("a", "player.a", changedAt = 10)
        val samePackage = media("b", "player.a", changedAt = 10)
        val otherPackage = media("0", "player.b", changedAt = 10)
        val items = listOf(otherPackage, samePackage, winner)
        val packages = setOf("player.a", "player.b")

        assertSame(winner, selectMedia(items, packages))
        assertSame(winner, selectMedia(items.reversed(), packages))
    }

    @Test
    fun emptySessionsOrAllowlistHaveNoSelection() {
        assertNull(selectMedia(emptyList(), setOf("com.luna.music")))
        assertNull(selectMedia(listOf(media("track")), emptySet()))
    }

    private fun message(key: String, time: Long, text: String = "Preview") =
        BridgeMessage(key, "Sender", text, time)

    private fun media(
        id: String,
        packageName: String = "com.luna.music",
        playing: Boolean = true,
        changedAt: Long = 10,
    ) = MediaSnapshot(id, packageName, "Track", "Artist", playing, changedAt)
}
