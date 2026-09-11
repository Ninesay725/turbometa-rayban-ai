package com.smartview.glassai.bridge

data class BridgeMessage(
    val notificationKey: String,
    val sender: String,
    val text: String,
    val timestamp: Long,
)

/** In-memory previews only. The service removes a key before replacing its posted snapshot. */
class WeChatInbox {
    private var previews: List<BridgeMessage> = emptyList()
    val messages: List<BridgeMessage> get() = previews

    fun update(messages: List<BridgeMessage>): List<BridgeMessage> {
        previews = (previews + messages).distinct().sortedByDescending { it.timestamp }.take(3)
        return previews
    }

    fun remove(notificationKey: String): List<BridgeMessage> {
        previews = previews.filterNot { it.notificationKey == notificationKey }
        return previews
    }

    fun clear() {
        previews = emptyList()
    }
}

data class MediaSnapshot(
    val id: String,
    val packageName: String,
    val title: String,
    val artist: String,
    val isPlaying: Boolean,
    val changedAt: Long,
    val artJpeg: ByteArray? = null,
    val canPlayPause: Boolean = true,
    val canNext: Boolean = true,
    val canPrevious: Boolean = true,
)

fun selectMedia(items: List<MediaSnapshot>, allowedPackages: Set<String>): MediaSnapshot? =
    items.filter { it.packageName in allowedPackages }.minWithOrNull(
        compareByDescending<MediaSnapshot> { it.isPlaying }
            .thenByDescending { it.changedAt }
            .thenBy { it.packageName }
            .thenBy { it.id },
    )
