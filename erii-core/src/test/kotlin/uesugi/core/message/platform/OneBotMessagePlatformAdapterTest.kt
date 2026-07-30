package uesugi.core.message.platform

import kotlinx.coroutines.runBlocking
import uesugi.common.data.MessageType
import uesugi.onebot.core.model.GroupMessageEvent
import uesugi.onebot.core.model.GroupSender
import uesugi.onebot.core.model.imageSegment
import uesugi.onebot.core.model.recordSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OneBotMessagePlatformAdapterTest {

    @Test
    fun `image parser falls back to file when url is absent`() = runBlocking {
        val event = GroupMessageEvent(
            time = 0,
            selfId = 10000,
            groupId = 20000,
            userId = 30000,
            message = listOf(imageSegment(file = "https://example.com/cat.jpg")),
            sender = GroupSender(30000, "Momo"),
        )

        val parsed = OneBotMessagePlatformAdapter().parseMessage(event, "10000")

        assertEquals(MessageType.IMAGE, parsed.messageType)
        assertEquals("[图片]", parsed.content)
        assertEquals("https://example.com/cat.jpg", parsed.imageUrl)
        assertEquals("jpg", parsed.imageFormat)
    }

    @Test
    fun `audio parser prefers url and infers its format`() = runBlocking {
        val event = event(
            recordSegment(
                file = "fallback.amr",
                url = "https://example.com/voice.mp3?token=test",
            )
        )

        val parsed = OneBotMessagePlatformAdapter().parseMessage(event, "10000")

        assertEquals(MessageType.AUDIO, parsed.messageType)
        assertEquals("[音频]", parsed.content)
        assertEquals("https://example.com/voice.mp3?token=test", parsed.audioUrl)
        assertEquals("mp3", parsed.audioFormat)
    }

    @Test
    fun `audio parser falls back to file when url is absent`() = runBlocking {
        val parsed = OneBotMessagePlatformAdapter().parseMessage(
            event(recordSegment(file = "file:///tmp/voice.wav")),
            "10000",
        )

        assertEquals(MessageType.AUDIO, parsed.messageType)
        assertEquals("file:///tmp/voice.wav", parsed.audioUrl)
        assertEquals("wav", parsed.audioFormat)
    }

    @Test
    fun `mixed media keeps only the first media resource`() = runBlocking {
        val audioFirst = OneBotMessagePlatformAdapter().parseMessage(
            event(
                recordSegment(file = "https://example.com/voice.ogg"),
                imageSegment(file = "https://example.com/cat.png"),
            ),
            "10000",
        )
        val imageFirst = OneBotMessagePlatformAdapter().parseMessage(
            event(
                imageSegment(file = "https://example.com/cat.png"),
                recordSegment(file = "https://example.com/voice.ogg"),
            ),
            "10000",
        )

        assertEquals(MessageType.AUDIO, audioFirst.messageType)
        assertEquals("https://example.com/voice.ogg", audioFirst.audioUrl)
        assertNull(audioFirst.imageUrl)
        assertEquals("[音频][图片]", audioFirst.content)

        assertEquals(MessageType.IMAGE, imageFirst.messageType)
        assertEquals("https://example.com/cat.png", imageFirst.imageUrl)
        assertNull(imageFirst.audioUrl)
        assertEquals("[图片][音频]", imageFirst.content)
    }

    private fun event(vararg segments: uesugi.onebot.core.model.MessageSegment) = GroupMessageEvent(
        time = 0,
        selfId = 10000,
        groupId = 20000,
        userId = 30000,
        message = segments.toList(),
        sender = GroupSender(30000, "Momo"),
    )
}
