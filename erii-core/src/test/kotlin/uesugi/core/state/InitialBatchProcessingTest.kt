package uesugi.core.state

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uesugi.common.data.HistoryEntity
import uesugi.common.data.HistoryTable
import uesugi.common.data.MessageType
import uesugi.common.data.ResourceTable
import uesugi.common.toolkit.ConfigHolder
import uesugi.common.toolkit.ConfigProvider
import uesugi.core.state.memory.FactGraphStoreFactory
import uesugi.core.state.memory.FactVectorStoreFactory
import uesugi.core.state.memory.FactsTable
import uesugi.core.state.memory.MemoryAgent
import uesugi.core.state.memory.MemoryRepository
import uesugi.core.state.memory.MemoryService
import uesugi.core.state.memory.MemoryStateTable
import uesugi.core.state.memory.UserProfileTable
import uesugi.core.state.summary.SummaryAgent
import uesugi.core.state.summary.SummaryRepository
import uesugi.core.state.summary.SummaryService
import uesugi.core.state.summary.SummaryStateTable
import uesugi.core.state.summary.SummaryTable
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class InitialBatchProcessingTest {
    @Test
    fun `memory first batch below threshold does not create cursor or call model`() = runBlocking {
        val database = createDatabase()
        insertMessages(database, count = 2) { "message-$it" }
        val executor = FailingPromptExecutor()
        val (service, repository) = memoryService(executor)

        val result = service.processGroupMemory("bot", "group", batchLimit = 3, minimumMessages = 3)

        assertEquals(0, result.processedCount)
        assertNull(repository.getMemoryState("bot", "group"))
        assertEquals(0, executor.calls.get())
    }

    @Test
    fun `summary first batch below threshold does not create cursor or call model`() = runBlocking {
        val database = createDatabase()
        insertMessages(database, count = 2) { "message-$it" }
        val executor = FailingPromptExecutor()
        val repository = SummaryRepository()
        val service = SummaryService(repository, SummaryAgent { executor })

        val result = service.processSummaryForGroup("bot", "group", batchLimit = 3, minimumMessages = 3)

        assertEquals(0, result.processedCount)
        assertNull(repository.getSummaryState("bot", "group"))
        assertEquals(0, executor.calls.get())
    }

    @Test
    fun `memory first run consumes only latest batch and does not continue into older history`() = runBlocking {
        val database = createDatabase()
        val ids = insertMessages(database, count = 6) { " " }
        val (service, repository) = memoryService(FailingPromptExecutor())

        val result = service.processGroupMemory("bot", "group", batchLimit = 3, minimumMessages = 3)

        assertEquals(3, result.processedCount)
        assertEquals(ids.last(), result.cursor)
        assertFalse(result.hasMore)
        assertEquals(ids.last(), repository.getMemoryState("bot", "group")?.lastProcessedHistoryId)
    }

    @Test
    fun `summary first run consumes only latest batch and does not continue into older history`() = runBlocking {
        val database = createDatabase()
        val ids = insertMessages(database, count = 6) { " " }
        val repository = SummaryRepository()
        val service = SummaryService(repository, SummaryAgent { FailingPromptExecutor() })

        val result = service.processSummaryForGroup("bot", "group", batchLimit = 3, minimumMessages = 3)

        assertEquals(3, result.processedCount)
        assertEquals(ids.last(), result.cursor)
        assertFalse(result.hasMore)
        assertEquals(ids.last(), repository.getSummaryState("bot", "group")?.lastProcessedHistoryId)
    }

    @Test
    fun `memory failed first batch keeps cursor absent for the same latest batch retry`() = runBlocking {
        val database = createDatabase()
        val ids = insertMessages(database, count = 3) { "message-$it" }
        val executor = FailingPromptExecutor()
        val (service, repository) = memoryService(executor)

        assertFailsWith<IllegalStateException> {
            service.processGroupMemory("bot", "group", batchLimit = 3, minimumMessages = 3)
        }
        assertNull(repository.getMemoryState("bot", "group"))
        assertEquals(ids, repository.getLatestHistories("bot", "group", 3).map { it.id })

        assertFailsWith<IllegalStateException> {
            service.processGroupMemory("bot", "group", batchLimit = 3, minimumMessages = 3)
        }
        assertNull(repository.getMemoryState("bot", "group"))
    }

    @Test
    fun `summary failed first batch keeps cursor absent for the same latest batch retry`() = runBlocking {
        val database = createDatabase()
        val ids = insertMessages(database, count = 3) { "message-$it" }
        val executor = FailingPromptExecutor()
        val repository = SummaryRepository()
        val service = SummaryService(repository, SummaryAgent { executor })

        assertFailsWith<IllegalStateException> {
            service.processSummaryForGroup("bot", "group", batchLimit = 3, minimumMessages = 3)
        }
        assertNull(repository.getSummaryState("bot", "group"))
        assertEquals(ids, repository.getLatestHistories("bot", "group", 3).map { it.id })

        assertFailsWith<IllegalStateException> {
            service.processSummaryForGroup("bot", "group", batchLimit = 3, minimumMessages = 3)
        }
        assertNull(repository.getSummaryState("bot", "group"))
    }

    private fun memoryService(executor: PromptExecutor): Pair<MemoryService, MemoryRepository> {
        val repository = MemoryRepository()
        val vectorStore = FactVectorStoreFactory()
        val graphStore = FactGraphStoreFactory()
        return MemoryService(
            memoryAgent = MemoryAgent(repository, vectorStore, graphStore, executor),
            memoryRepository = repository,
            factVectorStoreFactory = vectorStore,
            factGraphStoreFactory = graphStore
        ) to repository
    }

    private fun createDatabase(): Database {
        ConfigHolder.init(testConfigProvider())
        val database = Database.connect(
            url = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        TransactionManager.defaultDatabase = database
        transaction(database) {
            SchemaUtils.create(
                ResourceTable,
                HistoryTable,
                MemoryStateTable,
                UserProfileTable,
                FactsTable,
                SummaryTable,
                SummaryStateTable
            )
        }
        return database
    }

    private fun testConfigProvider(): ConfigProvider = Proxy.newProxyInstance(
        ConfigProvider::class.java.classLoader,
        arrayOf(ConfigProvider::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "getAgentMaxMessageLength" -> 10_000
            "getChoiceProvider" -> "OPENAI"
            "getLlmOpenAIModels" -> mapOf("lite" to "test", "pro" to "test")
            "isLlmCapabilityEnabled" -> true
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "InitialBatchTestConfigProvider"
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Double.TYPE -> 0.0
                else -> null
            }
        }
    } as ConfigProvider

    private fun insertMessages(
        database: Database,
        count: Int,
        content: (Int) -> String
    ): List<Int> = transaction(database) {
        (1..count).map { index ->
            HistoryEntity.new {
                botId = "bot"
                groupId = "group"
                userId = "user"
                nick = "user"
                messageType = MessageType.TEXT
                this.content = content(index)
            }.id.value
        }
    }

    private class FailingPromptExecutor : PromptExecutor() {
        val calls = AtomicInteger()

        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Message.Assistant {
            calls.incrementAndGet()
            error("expected prompt failure")
        }

        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Flow<StreamFrame> = error("not used")

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("not used")

        override fun close() = Unit
    }
}
