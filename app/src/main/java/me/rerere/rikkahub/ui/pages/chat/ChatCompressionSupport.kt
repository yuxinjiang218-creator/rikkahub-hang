package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.model.CompressionEvent
import me.rerere.rikkahub.data.model.compressionEventOrder

internal fun sortedCompressionEvents(events: List<CompressionEvent>): List<CompressionEvent> =
    events.sortedWith(compressionEventOrder)

internal fun renderedListIndexForMessage(
    globalMessageIndex: Int,
    compressionEvents: List<CompressionEvent>,
): Int {
    return globalMessageIndex + compressionEvents.count { it.boundaryIndex <= globalMessageIndex }
}

internal fun findCompressionListIndex(
    eventId: Long,
    compressionEvents: List<CompressionEvent>,
    messageCount: Int,
): Int? {
    var listIndex = 0
    var compressionIndex = 0
    for (messageIndex in 0..messageCount) {
        while (compressionIndex < compressionEvents.size &&
            compressionEvents[compressionIndex].boundaryIndex == messageIndex
        ) {
            if (compressionEvents[compressionIndex].id == eventId) {
                return listIndex
            }
            listIndex += 1
            compressionIndex += 1
        }
        if (messageIndex < messageCount) {
            listIndex += 1
        }
    }
    return null
}
