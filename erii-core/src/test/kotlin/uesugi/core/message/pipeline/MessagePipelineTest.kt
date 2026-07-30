package uesugi.core.message.pipeline

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.Path
import okio.Source
import okio.buffer
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uesugi.common.data.HistoryTable
import uesugi.common.data.MessageType
import uesugi.common.data.ResourceEntity
import uesugi.common.data.ResourceTable
import uesugi.common.message.MessageContext
import uesugi.common.message.ParsedMessage
import uesugi.core.component.storage.ObjectStorage
import uesugi.core.message.history.HistoryService
import uesugi.core.message.resource.ResourceService
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.*
import kotlin.test.*

class MessagePipelineTest {

    @Test
    fun `base64 audio is stored under audio directory with safe unknown extension`() = runBlocking {
        createDatabase()
        val storage = RecordingObjectStorage()
        val pipeline = pipeline(storage)
        val bytes = "base64-audio".encodeToByteArray()

        val history = pipeline.saveHistory(
            context(
                audioUrl = "base64://${Base64.getEncoder().encodeToString(bytes)}",
                audioFormat = null,
            )
        )

        assertEquals(MessageType.AUDIO, history.messageType)
        assertEquals("[音频]", history.content)
        assertNotNull(history.resource?.id)
        assertTrue(history.resource!!.url.startsWith("./audio/group-a/"))
        assertTrue(history.resource!!.url.endsWith(".bin"))
        assertContentEquals(bytes, storage.singleValue())
        transaction {
            assertEquals(1L, ResourceEntity.all().count())
        }
    }

    @Test
    fun `audio media reader supports local files and http urls`() = runBlocking {
        createDatabase()
        val storage = RecordingObjectStorage()
        val pipeline = pipeline(storage)
        val localBytes = "local-audio".encodeToByteArray()
        val remoteBytes = "remote-audio".encodeToByteArray()
        val localFile = Files.createTempFile("erii-audio", ".wav")
        Files.write(localFile, localBytes)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/voice.mp3") { exchange ->
            exchange.sendResponseHeaders(200, remoteBytes.size.toLong())
            exchange.responseBody.use { it.write(remoteBytes) }
        }
        server.start()

        try {
            val local = pipeline.saveHistory(
                context(
                    audioUrl = localFile.toUri().toString(),
                    audioFormat = "wav",
                )
            )
            val remote = pipeline.saveHistory(
                context(
                    audioUrl = "http://127.0.0.1:${server.address.port}/voice.mp3",
                    audioFormat = "mp3",
                )
            )

            assertTrue(local.resource!!.url.endsWith(".wav"))
            assertTrue(remote.resource!!.url.endsWith(".mp3"))
            assertTrue(storage.values().any { it.contentEquals(localBytes) })
            assertTrue(storage.values().any { it.contentEquals(remoteBytes) })
        } finally {
            server.stop(0)
            Files.deleteIfExists(localFile)
        }
    }

    @Test
    fun `same audio bytes reuse stored object by md5 while keeping history association`() = runBlocking {
        createDatabase()
        val storage = RecordingObjectStorage()
        val pipeline = pipeline(storage)
        val bytes = "same-audio".encodeToByteArray()
        val encoded = Base64.getEncoder().encodeToString(bytes)

        val first = pipeline.saveHistory(context(audioUrl = "base64://$encoded", audioFormat = "mp3"))
        val second = pipeline.saveHistory(context(audioUrl = "base64://$encoded", audioFormat = "mp3"))

        assertEquals(first.resource!!.url, second.resource!!.url)
        assertNotNull(first.resource!!.id)
        assertNotNull(second.resource!!.id)
        assertEquals(first.resource!!.id, second.resource!!.id)
        assertEquals(1, storage.putCount)
        transaction {
            assertEquals(1L, ResourceEntity.all().count())
        }
    }

    private fun pipeline(storage: ObjectStorage) = MessagePipeline(
        historyService = HistoryService(),
        resourceService = ResourceService(),
        storage = storage,
    )

    private fun context(audioUrl: String, audioFormat: String?) = MessageContext(
        botId = "bot-a",
        groupId = "group-a",
        senderId = "user-a",
        senderNick = "Alice",
        parsedMessage = ParsedMessage(
            content = "[音频]",
            isAtBot = false,
            messageType = MessageType.AUDIO,
            audioUrl = audioUrl,
            audioFormat = audioFormat,
        ),
    )

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

    private class RecordingObjectStorage : ObjectStorage {
        private val content = linkedMapOf<String, ByteArray>()
        var putCount = 0
            private set

        override fun put(path: Path, source: Source) {
            putCount++
            content[path.toString()] = source.buffer().use { it.readByteArray() }
        }

        override fun get(path: Path): Source =
            Buffer().write(content.getValue(path.toString()))

        override fun exists(path: Path): Boolean = content.containsKey(path.toString())

        override fun delete(path: Path) {
            content.remove(path.toString())
        }

        override fun list(dir: Path): List<Path> = emptyList()

        fun singleValue(): ByteArray = content.values.single()

        fun values(): Collection<ByteArray> = content.values
    }
}
