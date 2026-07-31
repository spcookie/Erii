package uesugi.core.message.resource

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uesugi.common.data.HistoryEntity
import uesugi.common.data.HistoryTable
import uesugi.common.data.MessageType
import uesugi.common.data.ResourceEntity
import uesugi.common.data.ResourceTable

class ResourceServiceTest {
    @Test
    fun `delete resource clears history reference before deleting resource`() {
        val database = createDatabase()
        val (resourceId, historyId) = transaction(database) {
            val resource = ResourceEntity.new {
                botId = "bot-a"
                groupId = "group-a"
                url = "images/old.png"
                fileName = "old.png"
                size = 42
                md5 = "md5-old"
            }
            val history = HistoryEntity.new {
                botId = "bot-a"
                groupId = "group-a"
                userId = "user-a"
                nick = "user"
                messageType = MessageType.IMAGE
                content = "old image"
                this.resource = resource
            }
            resource.id.value to history.id.value
        }

        assertTrue(ResourceService().deleteResource(resourceId))

        transaction(database) {
            assertNull(ResourceEntity.findById(resourceId))
            assertNull(HistoryEntity.findById(historyId)?.resource)
        }
    }

    private fun createDatabase(): Database {
        val database = Database.connect(
            url = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        TransactionManager.defaultDatabase = database
        transaction(database) {
            SchemaUtils.create(ResourceTable, HistoryTable)
        }
        return database
    }
}
