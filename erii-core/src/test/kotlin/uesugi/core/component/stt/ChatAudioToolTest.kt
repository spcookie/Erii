package uesugi.core.component.stt

import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uesugi.common.data.*
import uesugi.core.component.storage.ObjectStorage
import java.util.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatAudioToolTest {

    @Test
    fun `transcribe audio loads history resource and calls stt`() = runBlocking {
        createDatabase()
        val bytes = "voice-content".encodeToByteArray()
        val path = "./audio/group-a/voice.mp3"
        val historyId = createHistory(path, MessageType.AUDIO)
        val storage = RecordingObjectStorage(mapOf(path to bytes))
        var receivedFormat = ""
        var receivedFileName = ""
        var receivedBytes = byteArrayOf()
        val tool = ChatAudioTool(
            nativeAudio = false,
            objectStorage = storage,
            transcriber = { audio, format, fileName ->
                receivedBytes = audio
                receivedFormat = format
                receivedFileName = fileName
                "转写结果"
            },
        )

        val result = tool.transcribeAudio(historyId.toString())

        assertEquals("转写结果", result)
        assertContentEquals(bytes, receivedBytes)
        assertEquals("mp3", receivedFormat)
        assertEquals("voice.mp3", receivedFileName)
    }

    @Test
    fun `transcribe audio rejects non audio history`() = runBlocking {
        createDatabase()
        val historyId = createHistory("./image/group-a/cat.png", MessageType.IMAGE)
        var called = false
        val tool = ChatAudioTool(
            nativeAudio = false,
            objectStorage = RecordingObjectStorage(emptyMap()),
            transcriber = { _, _, _ ->
                called = true
                "unexpected"
            },
        )

        val result = tool.transcribeAudio(historyId.toString())

        assertTrue(result.contains("未找到ID为"))
        assertEquals(false, called)
    }

    @Test
    fun `native audio model receives direct handling hint when stt fails`() = runBlocking {
        createDatabase()
        val path = "./audio/group-a/voice.wav"
        val historyId = createHistory(path, MessageType.AUDIO)
        val tool = ChatAudioTool(
            nativeAudio = true,
            objectStorage = RecordingObjectStorage(mapOf(path to byteArrayOf(1, 2, 3))),
            transcriber = { _, _, _ -> error("provider failed") },
        )

        val result = tool.transcribeAudio(historyId.toString())

        assertTrue(result.contains("音频转写失败"))
        assertTrue(result.contains("原生支持音频理解"))
        assertTrue(result.contains("不要再调用 transcribeAudio"))
    }

    private fun createHistory(path: String, messageType: MessageType): Int = transaction {
        val resource = ResourceEntity.new {
            botId = "bot-a"
            groupId = "group-a"
            url = path
            fileName = path.substringAfterLast("/")
            size = 13
            md5 = UUID.randomUUID().toString()
        }
        HistoryEntity.new {
            botId = "bot-a"
            groupId = "group-a"
            userId = "user-a"
            nick = "Alice"
            this.messageType = messageType
            content = if (messageType == MessageType.AUDIO) "[音频]" else "[图片]"
            this.resource = resource
        }.id.value
    }

    private fun createDatabase(): Database {
        val database = Database.connect(
            url = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        TransactionManager.defaultDatabase = database
        transaction(database) {
            SchemaUtils.create(ResourceTable, HistoryTable)
        }
        return database
    }

    private class RecordingObjectStorage(
        values: Map<String, ByteArray>,
    ) : ObjectStorage {
        private val content = values.mapKeys { (path, _) -> path.toPath().toString() }

        override fun put(path: Path, source: Source) = error("not used")

        override fun get(path: Path): Source =
            Buffer().write(content.getValue(path.toString()))

        override fun exists(path: Path): Boolean = content.containsKey(path.toString())

        override fun delete(path: Path) = Unit

        override fun list(dir: Path): List<Path> = emptyList()
    }
}
