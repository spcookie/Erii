package uesugi.core.agent

import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import uesugi.common.ConfigBotRole
import uesugi.common.data.EmotionalTendencies
import uesugi.common.data.HistoryRecord
import uesugi.common.data.MessageType
import uesugi.common.data.ResourceRecord
import uesugi.common.event.InterruptionMode
import kotlin.test.*

class AudioPromptTest {

    @Test
    fun `audio capable prompt attaches original audio bytes`() = runBlocking {
        val bytes = "audio-content".encodeToByteArray()
        var reads = 0
        val prompt = buildPrompt(
            context = context { _, _ ->
                reads++
                MediaResource(bytes, "mp3", "voice.mp3")
            },
            supportsVision = false,
            supportsAudio = true,
        )

        val attachment = prompt.messages
            .flatMap { it.parts }
            .filterIsInstance<MessagePart.Attachment>()
            .single()
        val source = assertIs<AttachmentSource.Audio>(attachment.source)
        val content = assertIs<AttachmentContent.Binary.Bytes>(source.content)

        assertEquals(1, reads)
        assertEquals("mp3", source.format)
        assertEquals("voice.mp3", source.fileName)
        assertContentEquals(bytes, content.data)
    }

    @Test
    fun `audio disabled uses placeholder without reading object storage`() = runBlocking {
        var reads = 0
        val prompt = buildPrompt(
            context = context { _, _ ->
                reads++
                MediaResource(byteArrayOf(1), "mp3", "voice.mp3")
            },
            supportsVision = false,
            supportsAudio = false,
        )

        assertEquals(0, reads)
        assertTrue(prompt.messages.any { it.textContent().contains("[音频]") })
        assertTrue(prompt.messages.any { it.textContent().contains("[audio_id:1]") })
        assertFalse(prompt.messages.flatMap { it.parts }.any { it is MessagePart.Attachment })
    }

    @Test
    fun `missing invalid or failed audio falls back to placeholder`() = runBlocking {
        val contexts = listOf(
            context { _, _ -> null },
            context { _, _ -> MediaResource(byteArrayOf(1), "bin", "voice.bin") },
            context { _, _ -> error("storage failed") },
        )

        for (context in contexts) {
            val prompt = buildPrompt(
                context = context,
                supportsVision = false,
                supportsAudio = true,
            )
            assertTrue(prompt.messages.any { it.textContent().contains("[音频]") })
            assertTrue(prompt.messages.any { it.textContent().contains("[audio_id:1]") })
            assertFalse(prompt.messages.flatMap { it.parts }.any { it is MessagePart.Attachment })
        }
    }

    @Test
    fun `bot audio history is rendered as placeholder and never loaded`() = runBlocking {
        var reads = 0
        val prompt = buildPrompt(
            context = context(
                history = audioHistory(userId = "bot-a"),
            ) { _, _ ->
                reads++
                MediaResource(byteArrayOf(1), "mp3", "voice.mp3")
            },
            supportsVision = false,
            supportsAudio = true,
        )

        val call = prompt.messages
            .flatMap { it.parts }
            .filterIsInstance<MessagePart.Tool.Call>()
            .single()

        assertEquals(0, reads)
        assertTrue(call.args.contains("[音频]"))
    }

    private fun context(
        history: HistoryRecord = audioHistory(),
        mediaResource: suspend (HistoryRecord, Boolean) -> MediaResource?,
    ) = Context(
        currentBotId = "bot-a",
        groupId = "group-a",
        echo = "echo",
        botRole = ConfigBotRole(
            id = "test",
            name = "Test Bot",
            personalityTemplate = "test personality",
            character = "test",
            emoticon = EmotionalTendencies.JOY,
        ),
        impulse = { 0.0 },
        interruptionMode = InterruptionMode.Routine,
        behaviorProfile = { null },
        flow = { 50.0 },
        emotionPad = { null },
        facts = { emptyList() },
        userProfiles = { emptyList() },
        vocabulary = { emptyList() },
        summary = { null },
        histories = { listOf(history) },
        moreHistories = { emptyList() },
        rules = { emptyList() },
        admins = { emptyList() },
        memes = { 0 },
        meme = { null },
        mediaResource = mediaResource,
    )

    private fun audioHistory(userId: String = "user-a") = HistoryRecord(
        id = 1,
        botMark = "bot-a",
        groupId = "group-a",
        userId = userId,
        nick = "Alice",
        messageType = MessageType.AUDIO,
        content = "ignored audio content",
        resource = ResourceRecord(
            id = 10,
            botMark = "bot-a",
            groupId = "group-a",
            url = "./audio/group-a/voice.mp3",
            fileName = "voice.mp3",
            size = 13,
            md5 = "md5",
            createdAt = LocalDateTime(2026, 1, 1, 0, 0),
        ),
        createdAt = LocalDateTime(2026, 1, 1, 0, 0),
    )
}
