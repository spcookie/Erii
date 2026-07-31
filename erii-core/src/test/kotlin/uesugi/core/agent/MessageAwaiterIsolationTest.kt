package uesugi.core.agent

import kotlinx.datetime.LocalDateTime
import uesugi.common.data.HistoryRecord
import uesugi.common.data.MessageType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageAwaiterIsolationTest {
    @Test
    fun `history message must match both bot and group`() {
        val current = history(botId = "bot-a", groupId = "group-a", userId = "user")

        assertTrue(current.isMessageFor(botId = "bot-a", groupId = "group-a"))
        assertFalse(current.isMessageFor(botId = "bot-b", groupId = "group-a"))
        assertFalse(current.isMessageFor(botId = "bot-a", groupId = "group-b"))
    }

    @Test
    fun `bot own history does not wake its message awaiter`() {
        val botMessage = history(botId = "bot-a", groupId = "group-a", userId = "bot-a")

        assertFalse(botMessage.isMessageFor(botId = "bot-a", groupId = "group-a"))
    }

    private fun history(botId: String, groupId: String, userId: String) = HistoryRecord(
        botId = botId,
        groupId = groupId,
        userId = userId,
        nick = userId,
        messageType = MessageType.TEXT,
        content = "message",
        createdAt = LocalDateTime(2026, 7, 31, 12, 0)
    )
}
