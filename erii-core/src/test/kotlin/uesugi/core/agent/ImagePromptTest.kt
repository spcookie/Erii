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

class ImagePromptTest {

    @Test
    fun `vision capable prompt reads image through context callback`() = runBlocking {
        val bytes = "image-content".encodeToByteArray()
        var reads = 0
        var requestedThumbnail = true
        val prompt = buildPrompt(
            context = context { _, useThumbnail ->
                reads++
                requestedThumbnail = useThumbnail
                MediaResource(bytes, "png", "cat.png")
            },
            supportsVision = true,
            supportsAudio = false,
        )

        val attachment = prompt.messages
            .flatMap { it.parts }
            .filterIsInstance<MessagePart.Attachment>()
            .single()
        val source = assertIs<AttachmentSource.Image>(attachment.source)
        val content = assertIs<AttachmentContent.Binary.Bytes>(source.content)
        val attachmentMessage = prompt.messages.single { attachment in it.parts }

        assertEquals(1, reads)
        assertFalse(requestedThumbnail)
        assertTrue(attachmentMessage.textContent().contains("[Alice](user-a):"))
        assertEquals("png", source.format)
        assertEquals("cat.png", source.fileName)
        assertContentEquals(bytes, content.data)
    }

    @Test
    fun `vision disabled uses image id without reading context resource`() = runBlocking {
        var reads = 0
        val prompt = buildPrompt(
            context = context { _, _ ->
                reads++
                MediaResource(byteArrayOf(1), "png", "cat.png")
            },
            supportsVision = false,
            supportsAudio = false,
        )

        assertEquals(0, reads)
        assertTrue(prompt.messages.any { it.textContent().contains("[image_id:1]") })
        assertFalse(prompt.messages.flatMap { it.parts }.any { it is MessagePart.Attachment })
    }

    @Test
    fun `older images request thumbnails while latest image requests original`() = runBlocking {
        val thumbnailRequests = mutableListOf<Boolean>()

        buildPrompt(
            context = context(
                histories = listOf(imageHistory(1), imageHistory(2)),
            ) { history, useThumbnail ->
                thumbnailRequests += useThumbnail
                MediaResource(byteArrayOf(history.id!!.toByte()), "png", "image-${history.id}.png")
            },
            supportsVision = true,
            supportsAudio = false,
        )

        assertEquals(listOf(true, false), thumbnailRequests)
    }

    @Test
    fun `bot image history tool call retains image id`() = runBlocking {
        var reads = 0
        val prompt = buildPrompt(
            context = context(
                histories = listOf(imageHistory(userId = "bot-a")),
            ) { _, _ ->
                reads++
                MediaResource(byteArrayOf(1), "png", "cat.png")
            },
            supportsVision = true,
            supportsAudio = false,
        )

        val call = prompt.messages
            .flatMap { it.parts }
            .filterIsInstance<MessagePart.Tool.Call>()
            .single()

        assertEquals(0, reads)
        assertTrue(call.args.contains("[image_id:1]"))
        assertTrue(call.args.contains("[图片]"))
    }

    private fun context(
        histories: List<HistoryRecord> = listOf(imageHistory()),
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
        histories = { histories },
        moreHistories = { emptyList() },
        rules = { emptyList() },
        admins = { emptyList() },
        memes = { 0 },
        meme = { null },
        mediaResource = mediaResource,
    )

    private fun imageHistory(id: Int = 1, userId: String = "user-a") = HistoryRecord(
        id = id,
        botId = "bot-a",
        groupId = "group-a",
        userId = userId,
        nick = "Alice",
        messageType = MessageType.IMAGE,
        content = "[图片]",
        resource = ResourceRecord(
            id = id + 9,
            botId = "bot-a",
            groupId = "group-a",
            url = "./image/group-a/cat.png",
            fileName = "cat.png",
            size = 13,
            md5 = "md5",
            createdAt = LocalDateTime(2026, 1, 1, 0, 0),
        ),
        createdAt = LocalDateTime(2026, 1, 1, 0, 0),
    )
}
