package uesugi.core.state.dispatch

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uesugi.common.data.HistoryEntity
import uesugi.common.data.HistoryTable
import uesugi.common.data.MessageType
import uesugi.common.data.ResourceTable
import uesugi.core.state.emotion.EmotionRepository
import uesugi.core.state.evolution.EvolutionRepository
import uesugi.core.state.evolution.EvolutionService
import uesugi.core.state.evolution.EvolutionStateTable
import uesugi.core.state.flow.FlowRepository
import uesugi.core.state.memory.MemoryRepository
import uesugi.core.state.summary.SummaryRepository
import uesugi.core.state.volition.VolitionRepository
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days

class StateCursorRepositoryTest {
    @Test
    fun `latest window repositories return newest messages in id order`() {
        val database = createDatabase()
        val ids = insertMessages(database, "bot-a", "group-a", 6)
        val cursor = ids.first()
        val expected = ids.takeLast(3)

        assertEquals(
            expected,
            EmotionRepository().getLatestNewMessages("bot-a", "group-a", cursor, 3).map { it.id.value })
        assertEquals(
            expected,
            FlowRepository().getLatestHistoriesToProcess("bot-a", "group-a", cursor, 3).map { it.id.value })
        assertEquals(
            expected,
            VolitionRepository().getLatestHistoriesToProcess("bot-a", "group-a", cursor, 3).map { it.id.value })
        assertEquals(
            expected,
            MemoryRepository().getLatestHistories("bot-a", "group-a", 3).map { it.id })
        assertEquals(
            expected,
            SummaryRepository().getLatestHistories("bot-a", "group-a", 3).map { it.id })
    }

    @Test
    fun `sequential repository starts immediately after cursor`() {
        val database = createDatabase()
        val ids = insertMessages(database, "bot-a", "group-a", 6)

        val repository = MemoryRepository()
        val actual = repository.getHistoriesToProcess("bot-a", "group-a", ids.first(), 2).map { it.id }

        assertEquals(ids.drop(1).take(2), actual)
        assertEquals(
            ids.drop(1).take(2),
            repository.getUnprocessedMessages("bot-a", "group-a", null, ids.first(), 2).map { it.id }
        )
        assertEquals(
            ids.drop(1).take(2),
            SummaryRepository().getHistoriesToProcess("bot-a", "group-a", ids.first(), 2).map { it.id }
        )
    }

    @Test
    fun `evolution cursor is isolated and messages are read sequentially`() {
        val database = createDatabase()
        val groupAIds = insertMessages(database, "bot-a", "group-a", 5)
        insertMessages(database, "bot-a", "group-b", 1)
        insertMessages(database, "bot-b", "group-a", 1)
        val repository = EvolutionRepository()

        repository.updateState("bot-a", "group-a", groupAIds[1])
        repository.updateState("bot-b", "group-a", 999)

        assertEquals(groupAIds[1], repository.getState("bot-a", "group-a")?.lastProcessedHistoryId)
        assertEquals(999, repository.getState("bot-b", "group-a")?.lastProcessedHistoryId)
        assertEquals(
            groupAIds.drop(2).take(2),
            repository.getMessagesAfter("bot-a", "group-a", groupAIds[1], 2).map { it.id }
        )
        assertEquals(setOf("group-a", "group-b"), repository.findGroupsNeedProcessing("bot-a").toSet())
    }

    @Test
    fun `evolution initial window uses the newest messages in id order`() {
        val database = createDatabase()
        insertMessages(database, "bot-a", "group-a", 6)

        assertEquals(
            listOf("message-4", "message-5", "message-6"),
            EvolutionService().getMostActiveMessages("bot-a", "group-a", 3, 1.days)
        )
    }

    private fun createDatabase(): Database {
        val database = Database.connect(
            url = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        TransactionManager.defaultDatabase = database
        transaction(database) {
            SchemaUtils.create(ResourceTable, HistoryTable, EvolutionStateTable)
        }
        return database
    }

    private fun insertMessages(database: Database, botId: String, groupId: String, count: Int): List<Int> =
        transaction(database) {
            (1..count).map { index ->
                HistoryEntity.new {
                    this.botId = botId
                    this.groupId = groupId
                    userId = "user-$index"
                    nick = "user"
                    messageType = MessageType.TEXT
                    content = "message-$index"
                }.id.value
            }
        }
}
