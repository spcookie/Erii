package uesugi.core.chat

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDateTime
import uesugi.common.data.HistoryRecord
import uesugi.common.data.MessageType
import uesugi.common.data.ResourceRecord
import uesugi.config.ChatBridgeConst.MOCK_BOT_ID
import uesugi.config.ChatBridgeConst.MOCK_GROUP_ID
import uesugi.config.ChatBridgeConst.MOCK_USER_ID
import uesugi.core.message.history.HistoryService
import uesugi.onebot.core.config.OneBotConfig
import uesugi.onebot.mock.MockBot
import uesugi.onebot.mock.storage.InMemoryStorage
import kotlin.test.*

class ChatBridgeHistoryTest {

    @Test
    fun `sending to the mock bot does not wait for the eventual reply`() = runBlocking {
        val mockBot = MockBot(
            config = OneBotConfig(selfId = MOCK_BOT_ID, appName = "chat-bridge-test"),
            storage = InMemoryStorage(selfId = MOCK_BOT_ID),
        )
        mockBot.start()
        try {
            mockBot.addGroup(MOCK_GROUP_ID, "CLI Chat")
            mockBot.addUser(MOCK_USER_ID, "You")
            mockBot.addGroupMember(MOCK_GROUP_ID, MOCK_BOT_ID, "Erii")
            mockBot.addGroupMember(MOCK_GROUP_ID, MOCK_USER_ID, "You")

            val bridge = ChatBridge(HistoryService())
            ChatBridge::class.java.getDeclaredField("mockBot").apply {
                isAccessible = true
                set(bridge, mockBot)
            }

            withTimeout(2_000) {
                bridge.sendMessage("request-1", "hello")
            }
        } finally {
            mockBot.stop()
        }
    }

    @Test
    fun `history entry removes only the automatic bot mention`() {
        val automaticMention = history(content = "@$MOCK_BOT_ID  hello @someone")
            .toChatHistoryEntry()
        val otherMention = history(content = "@someone hello")
            .toChatHistoryEntry()
        val botMention = history(userId = MOCK_BOT_ID.toString(), content = "@$MOCK_BOT_ID hello")
            .toChatHistoryEntry()

        assertEquals("hello @someone", automaticMention.content)
        assertEquals("@someone hello", otherMention.content)
        assertEquals("@$MOCK_BOT_ID hello", botMention.content)
    }

    @Test
    fun `history entry exposes image metadata without storage path`() {
        val resource = resource()
        val entry = history(messageType = MessageType.IMAGE, resource = resource)
            .toChatHistoryEntry()

        assertEquals(MessageType.IMAGE, entry.messageType)
        assertTrue(entry.hasImage)
        assertEquals("[CQ:image,file=erii-history://42,historyId=42]", entry.content)
        assertFalse(entry.content.contains(resource.url))
    }

    @Test
    fun `history entry keeps caption while converting image placeholder to cq segment`() {
        val entry = history(content = "look [图片] here", messageType = MessageType.IMAGE, resource = resource())
            .toChatHistoryEntry()

        assertEquals("look [CQ:image,file=erii-history://42,historyId=42] here", entry.content)
    }

    @Test
    fun `image resource is restricted to mock chat image history`() {
        val resource = resource()

        assertSame(resource, history(messageType = MessageType.IMAGE, resource = resource).chatImageResourceOrNull())
        assertSame(resource, history(messageType = MessageType.TEXT, resource = resource).chatImageResourceOrNull())
        assertTrue(history(messageType = MessageType.TEXT, resource = resource).toChatHistoryEntry().hasImage)
        assertNull(history(botId = "other", messageType = MessageType.IMAGE, resource = resource).chatImageResourceOrNull())
        assertNull(history(groupId = "other", messageType = MessageType.IMAGE, resource = resource).chatImageResourceOrNull())
        assertFalse(history(messageType = MessageType.IMAGE).toChatHistoryEntry().hasImage)
    }

    private fun history(
        botId: String = MOCK_BOT_ID.toString(),
        groupId: String = MOCK_GROUP_ID.toString(),
        userId: String = MOCK_USER_ID.toString(),
        content: String = "[图片]",
        messageType: MessageType = MessageType.TEXT,
        resource: ResourceRecord? = null,
    ) = HistoryRecord(
        id = 42,
        botId = botId,
        groupId = groupId,
        userId = userId,
        nick = "You",
        messageType = messageType,
        content = content,
        resource = resource,
        createdAt = LocalDateTime(2026, 7, 16, 12, 0),
    )

    private fun resource() = ResourceRecord(
        id = 7,
        botId = MOCK_BOT_ID.toString(),
        groupId = MOCK_GROUP_ID.toString(),
        url = "./image/mock/example.png",
        fileName = "example.png",
        size = 128,
        md5 = "abc",
        createdAt = LocalDateTime(2026, 7, 16, 12, 0),
    )
}
