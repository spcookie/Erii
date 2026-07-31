package uesugi.core.cleanup

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import okio.Path
import okio.Source
import okio.Timeout
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uesugi.common.data.ResourceEntity
import uesugi.common.data.ResourceTable
import uesugi.common.data.HistoryTable
import uesugi.core.component.storage.ObjectStorage
import uesugi.core.message.resource.ResourceService
import uesugi.core.message.resource.ThumbnailService
import uesugi.core.state.meme.MemeData.MemeEntity
import uesugi.core.state.meme.MemeData.MemeTable
import uesugi.core.state.meme.MemeRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days

class ResourceCleanupServiceTest {
    @Test
    fun `old resources referenced by memes are skipped while later resources are deleted`() {
        val database = createDatabase()
        val oldTime = Clock.System.now().minus(10.days).toLocalDateTime(TimeZone.currentSystemDefault())
        val (memeResourceId, plainResourceId) = transaction(database) {
            val memeResource = ResourceEntity.new {
                botId = "bot-a"
                groupId = "group-a"
                url = "images/meme.png"
                fileName = "meme.png"
                size = 10
                md5 = "meme-md5"
                createdAt = oldTime
            }
            val plainResource = ResourceEntity.new {
                botId = "bot-a"
                groupId = "group-a"
                url = "images/plain.png"
                fileName = "plain.png"
                size = 10
                md5 = "plain-md5"
                createdAt = oldTime
            }
            MemeEntity.new {
                botId = "bot-a"
                groupId = "group-a"
                resourceId = memeResource.id.value
                md5 = "meme-md5"
            }
            memeResource.id.value to plainResource.id.value
        }
        val storage = RecordingObjectStorage()
        val service = ResourceCleanupService(
            resourceService = ResourceService(),
            storage = storage,
            thumbnailService = ThumbnailService(storage),
            memeRepository = MemeRepository()
        )

        val deleted = service.cleanupOldResources(retentionDays = 7, batchSize = 1)

        assertEquals(1, deleted)
        assertEquals(listOf("images/plain.png"), storage.deleted.map { it.toString() })
        transaction(database) {
            assertNotNull(ResourceEntity.findById(memeResourceId))
            assertNull(ResourceEntity.findById(plainResourceId))
        }
    }

    private fun createDatabase(): Database {
        val database = Database.connect(
            url = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        TransactionManager.defaultDatabase = database
        transaction(database) {
            SchemaUtils.create(ResourceTable, HistoryTable, MemeTable)
        }
        return database
    }

    private class RecordingObjectStorage : ObjectStorage {
        val deleted = mutableListOf<Path>()

        override fun put(path: Path, source: Source) = Unit

        override fun get(path: Path): Source = EmptySource

        override fun exists(path: Path): Boolean = false

        override fun delete(path: Path) {
            deleted += path
        }

        override fun list(dir: Path): List<Path> = emptyList()
    }

    private object EmptySource : Source {
        override fun close() = Unit

        override fun read(sink: okio.Buffer, byteCount: Long): Long = -1

        override fun timeout(): Timeout = Timeout.NONE
    }
}
