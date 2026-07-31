package uesugi.core.message.history

import kotlinx.datetime.LocalDateTime
import uesugi.common.data.HistoryRecord
import uesugi.common.data.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryContentTruncationTest {

    @Test
    fun `truncates content and appends ellipsis`() {
        assertEquals("abc...", "abcdef".truncateHistoryContent(3))
        assertEquals("abc", "abc".truncateHistoryContent(3))
        assertEquals("...", "abc".truncateHistoryContent(-1))
    }

    @Test
    fun `nullable content falls back to empty string`() {
        val content: String? = null

        assertEquals("", content.orEmptyTruncatedHistoryContent(3))
    }

    @Test
    fun `history truncation does not mutate source record`() {
        val source = historyRecord("abcdef")

        val truncated = source.truncateContent(3)

        assertEquals("abcdef", source.content)
        assertEquals("abc...", truncated.content)
    }

    @Test
    fun `history without content is returned unchanged`() {
        val source = historyRecord(null)

        val truncated = source.truncateContent(3)

        assertNull(truncated.content)
        assertEquals(source, truncated)
    }

    private fun historyRecord(content: String?) = HistoryRecord(
        botId = "bot",
        groupId = "group",
        userId = "user",
        nick = "nick",
        messageType = MessageType.TEXT,
        content = content,
        createdAt = LocalDateTime(2026, 1, 1, 0, 0)
    )
}
