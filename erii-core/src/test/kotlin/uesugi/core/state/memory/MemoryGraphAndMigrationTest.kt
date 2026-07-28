package uesugi.core.state.memory

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import uesugi.config.migration
import uesugi.config.migrationIf
import uesugi.core.manage.ManageListQuery
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

class MemoryGraphAndMigrationTest {
    @Test
    fun `conflict candidates are limited to the same subject scope and keyword`() {
        createFactsDatabase()
        val repository = MemoryRepository()
        val matching = repository.createFact(
            "bot-a", "group-a", "居住地", "用户现在住在杭州", listOf("杭州"), "user-a", Scopes.USER
        )
        repository.createFact(
            "bot-a", "group-a", "居住地", "另一个用户住在上海", listOf("上海"), "user-b", Scopes.USER
        )
        repository.createFact(
            "bot-a", "group-a", "工作", "用户在某公司工作", emptyList(), "user-a", Scopes.USER
        )
        repository.createFact(
            "bot-a", "group-a", "居住地", "群体所在地", emptyList(), "", Scopes.GROUP
        )
        val existing = repository.getValidFacts("bot-a", "group-a")

        val candidates = selectConflictCandidates(
            listOf(
                ExtractedFact(
                    keyword = "居住地",
                    description = "用户已经搬到重庆",
                    entities = listOf("重庆"),
                    subjects = "user-a",
                    scope = Scopes.USER
                )
            ),
            existing
        )

        assertEquals(listOf(matching), candidates.map { it.id })
        assertFalse(isDeleteBackedBySuccessfulAdd(candidates.single(), emptyList()))
        assertTrue(
            isDeleteBackedBySuccessfulAdd(
                candidates.single(),
                listOf(
                    ExtractedFact(
                        keyword = "居住地",
                        description = "用户已经搬到重庆",
                        entities = listOf("重庆"),
                        subjects = "user-a",
                        scope = Scopes.USER
                    )
                )
            )
        )
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun `expired fact cleanup keeps recently deprecated facts for retention window`() {
        createFactsDatabase()
        val repository = MemoryRepository()
        val oldId = repository.createFact(
            "bot-a", "group-a", "old", "old fact", emptyList(), "user-a", Scopes.USER
        )
        val recentId = repository.createFact(
            "bot-a", "group-a", "recent", "recent fact", emptyList(), "user-a", Scopes.USER
        )
        val now = Clock.System.now()
        val timeZone = TimeZone.currentSystemDefault()
        transaction {
            FactsTable.update({ FactsTable.id eq oldId }) {
                it[FactsTable.validTo] = (now - 31.days).toLocalDateTime(timeZone)
            }
            FactsTable.update({ FactsTable.id eq recentId }) {
                it[FactsTable.validTo] = (now - 1.days).toLocalDateTime(timeZone)
            }
        }

        val deleted = repository.deleteExpiredFacts((now - 30.days).toLocalDateTime(timeZone))

        assertEquals(listOf(oldId), deleted.map { it.id })
        assertEquals(null, repository.getFactById(oldId))
        assertTrue(repository.getFactById(recentId) != null)
    }

    @Test
    fun `status fact size counts only valid facts while management lists all facts`() {
        createFactsDatabase()
        val repository = MemoryRepository()
        val activeId = repository.createFact(
            "bot-a", "group-a", "active", "active fact", listOf("杭州"), "user-a", Scopes.USER
        )
        val invalidId = repository.createFact(
            "bot-a", "group-a", "invalid", "invalid fact", listOf("上海"), "user-a", Scopes.USER
        )
        repository.deprecateFactsById("bot-a", "group-a", invalidId, Scopes.USER)
        repository.createFact(
            "bot-a", "group-b", "other", "other group fact", emptyList(), "user-a", Scopes.USER
        )
        val vectorStore = RecordingVectorStoreFactory()
        val graphStore = RecordingGraphStoreFactory()
        val service = MemoryService(
            memoryAgent = MemoryAgent(repository, vectorStore, graphStore, FailingPromptExecutor()),
            memoryRepository = repository,
            factVectorStoreFactory = vectorStore,
            factGraphStoreFactory = graphStore
        )

        assertEquals(1L, service.getFactSize("bot-a", "group-a"))
        assertEquals(listOf(activeId), service.getAllFactsByGroup("bot-a", "group-a").first.map { it.id.value })

        val (allFacts, total) = service.getAllFactsByGroupForManagement("bot-a", "group-a")
        assertEquals(2, total)
        assertEquals(setOf(activeId, invalidId), allFacts.map { it.id.value }.toSet())
        assertEquals(
            mapOf(activeId to true, invalidId to false),
            allFacts.associate { it.id.value to it.toRecord().valid }
        )

        val (invalidFacts, invalidTotal) = service.getAllFactsByGroupForManagement(
            "bot-a",
            "group-a",
            ManageListQuery(search = "invalid")
        )
        assertEquals(1, invalidTotal)
        assertEquals(listOf(invalidId), invalidFacts.map { it.id.value })

        val (matchingFacts, matchingTotal) = service.getAllFactsByGroupForManagement(
            "bot-a",
            "group-a",
            ManageListQuery(search = "active fact")
        )
        assertEquals(1, matchingTotal)
        assertEquals(listOf(activeId), matchingFacts.map { it.id.value })

        val (sortedByValidity, _) = service.getAllFactsByGroupForManagement(
            "bot-a",
            "group-a",
            ManageListQuery(sortBy = "valid", ascending = true)
        )
        assertEquals(listOf(invalidId, activeId), sortedByValidity.map { it.id.value })
    }

    @Test
    fun `management fact paging is applied after newest-first ordering`() {
        createFactsDatabase()
        val repository = MemoryRepository()
        repository.createFact("bot-a", "group-a", "first", "first fact", emptyList(), "user-a", Scopes.USER)
        val secondId = repository.createFact(
            "bot-a", "group-a", "second", "second fact", emptyList(), "user-a", Scopes.USER
        )
        val vectorStore = RecordingVectorStoreFactory()
        val graphStore = RecordingGraphStoreFactory()
        val service = MemoryService(
            memoryAgent = MemoryAgent(repository, vectorStore, graphStore, FailingPromptExecutor()),
            memoryRepository = repository,
            factVectorStoreFactory = vectorStore,
            factGraphStoreFactory = graphStore
        )

        val (page, total) = service.getAllFactsByGroupForManagement(
            "bot-a",
            "group-a",
            ManageListQuery(offset = 1, limit = 1, sortBy = "id", ascending = true)
        )

        assertEquals(2, total)
        assertEquals(listOf(secondId), page.map { it.id.value })
    }

    @Test
    fun `orphan store cleanup keeps active directories and removes inactive directories`() {
        val root = Files.createTempDirectory("erii-store-cleanup-test")
        try {
            Files.createDirectories(root.resolve("active"))
            Files.createDirectories(root.resolve("orphan"))
            Files.writeString(root.resolve("orphan").resolve("data"), "stale")
            Files.writeString(root.resolve("not-a-store"), "keep")
            val closed = mutableListOf<String>()

            val removed = removeOrphanStoreDirectories(root, setOf("active"), closed::add)

            assertEquals(listOf("orphan"), removed)
            assertEquals(listOf("orphan"), closed)
            assertTrue(Files.isDirectory(root.resolve("active")))
            assertTrue(Files.exists(root.resolve("not-a-store")))
            assertTrue(Files.notExists(root.resolve("orphan")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `migration adds entities column with empty list default`() {
        val database = createLegacyFactsDatabase()

        migration(database)

        val repository = MemoryRepository()
        val legacy = repository.getValidFacts("bot-a", "group-a").single()
        assertEquals(emptyList(), legacy.entities)

        val createdId = repository.createFact(
            "bot-a", "group-a", "new", "new fact", listOf("entity"), "user-a", Scopes.USER
        )
        assertEquals(listOf("entity"), repository.getValidFacts("bot-a", "group-a").single { it.id == createdId }.entities)
    }

    @Test
    fun `startup init adds entities column even when migration flag is disabled`() {
        val database = createLegacyFactsDatabase()

        migrationIf(false, database)

        val repository = MemoryRepository()
        val legacy = repository.getValidFacts("bot-a", "group-a").single()
        assertEquals(emptyList(), legacy.entities)

        val createdId = repository.createFact(
            "bot-a", "group-a", "new", "new fact", listOf("entity"), "user-a", Scopes.USER
        )
        assertEquals(listOf("entity"), repository.getValidFacts("bot-a", "group-a").single { it.id == createdId }.entities)
    }

    @Test
    fun `agent fact recall keeps graph expansion inside current bot group and subjects`() = runBlocking {
        createFactsDatabase()
        val repository = MemoryRepository()
        val sameGroupAllowed =
            repository.createFact("bot-a", "group-a", "same", "same group fact", listOf("杭州"), "user-a", Scopes.USER)
        val otherGroup = repository.createFact("bot-a", "group-b", "other group", "other group fact", listOf("杭州"), "user-a", Scopes.USER)
        val otherBot = repository.createFact("bot-b", "group-a", "other bot", "other bot fact", listOf("杭州"), "user-a", Scopes.USER)
        val otherUser = repository.createFact("bot-a", "group-a", "other user", "other user fact", listOf("杭州"), "user-b", Scopes.USER)
        val seed = repository.createFact("bot-a", "group-a", "seed", "seed fact", listOf("杭州"), "user-a", Scopes.USER)

        val graphStore = RecordingGraphStoreFactory(
            factsToEntities = mapOf(seed to listOf("杭州")),
            entitiesToFacts = mapOf("杭州" to listOf(seed, sameGroupAllowed, otherGroup, otherBot, otherUser))
        )
        val vectorStore = RecordingVectorStoreFactory(
            keywordResults = listOf(FactSearchResult("fact_bot-a_group-a_$seed", seed, "seed fact", 0.91f))
        )
        val service = MemoryService(
            memoryAgent = MemoryAgent(repository, vectorStore, graphStore, FailingPromptExecutor()),
            memoryRepository = repository,
            factVectorStoreFactory = vectorStore,
            factGraphStoreFactory = graphStore
        )

        val facts = service.recallFactsForAgent("bot-a", "group-a", listOf("user-a"), query = "seed", candidateLimit = 1, graphLimit = 2)

        assertEquals(listOf("same", "seed"), facts.map { it.keyword }.sorted())
    }

    @Test
    fun `agent fact recall merges bm25 and vector filters score and limits graph expansion`() = runBlocking {
        createFactsDatabase()
        val repository = MemoryRepository()
        val bm25High = repository.createFact("bot-a", "group-a", "bm25", "bm25 fact", listOf("杭州"), "user-a", Scopes.USER)
        val both = repository.createFact("bot-a", "group-a", "both", "both fact", listOf("杭州"), "user-a", Scopes.USER)
        val vectorHigh = repository.createFact("bot-a", "group-a", "vector", "vector fact", listOf("杭州"), "user-a", Scopes.USER)
        val lowScore = repository.createFact("bot-a", "group-a", "low", "low score fact", listOf("杭州"), "user-a", Scopes.USER)
        val graphFirst = repository.createFact("bot-a", "group-a", "graph first", "graph first fact", listOf("杭州"), "user-a", Scopes.USER)
        val graphSecond = repository.createFact("bot-a", "group-a", "graph second", "graph second fact", listOf("杭州"), "user-a", Scopes.USER)
        val graphThird = repository.createFact("bot-a", "group-a", "graph third", "graph third fact", listOf("杭州"), "user-a", Scopes.USER)

        val vectorStore = RecordingVectorStoreFactory(
            keywordResults = listOf(
                FactSearchResult("fact_bot-a_group-a_$bm25High", bm25High, "bm25 fact", 0.92f),
                FactSearchResult("fact_bot-a_group-a_$both", both, "both fact", 0.74f),
                FactSearchResult("fact_bot-a_group-a_$lowScore", lowScore, "low score fact", 0.69f)
            ),
            searchResults = listOf(
                FactSearchResult("fact_bot-a_group-a_$both", both, "both fact", 0.88f),
                FactSearchResult("fact_bot-a_group-a_$vectorHigh", vectorHigh, "vector fact", 0.72f),
                FactSearchResult("fact_bot-a_group-a_$lowScore", lowScore, "low score fact", 0.68f)
            )
        )
        val graphStore = RecordingGraphStoreFactory(
            factsToEntities = mapOf(
                bm25High to listOf("杭州"),
                both to listOf("杭州"),
                vectorHigh to listOf("杭州")
            ),
            entitiesToFacts = mapOf("杭州" to listOf(graphFirst, graphSecond, graphThird))
        )
        val service = MemoryService(
            memoryAgent = MemoryAgent(repository, vectorStore, graphStore, FailingPromptExecutor()),
            memoryRepository = repository,
            factVectorStoreFactory = vectorStore,
            factGraphStoreFactory = graphStore
        )

        val facts = service.recallFactsForAgent("bot-a", "group-a", listOf("user-a"), query = "杭州")

        assertEquals(listOf(bm25High, both, vectorHigh, graphFirst, graphSecond), facts.map { it.id.value })
        assertEquals(listOf(3), vectorStore.keywordTopKs)
        assertEquals(listOf(3), vectorStore.searchTopKs)
        assertTrue(facts.none { it.id.value == lowScore })
        assertTrue(facts.none { it.id.value == graphThird })
    }

    @Test
    fun `manual fact writes keep graph and vector stores in sync`() = runBlocking {
        createFactsDatabase()
        val repository = MemoryRepository()
        val vectorStore = RecordingVectorStoreFactory()
        val graphStore = RecordingGraphStoreFactory()
        val service = MemoryService(
            memoryAgent = MemoryAgent(repository, vectorStore, graphStore, FailingPromptExecutor()),
            memoryRepository = repository,
            factVectorStoreFactory = vectorStore,
            factGraphStoreFactory = graphStore
        )

        val created = service.createFact("bot-a", "group-a", "new", "new fact", listOf("杭州"), "user-a", Scopes.USER)!!
        val updated = service.updateFact(
            "bot-a",
            "group-a",
            created.id,
            "updated",
            "updated fact",
            listOf("重庆"),
            "user-a",
            Scopes.USER
        )!!
        service.deleteFact("bot-a", "group-a", updated.id)

        assertEquals(listOf(created.id, updated.id), graphStore.added)
        assertEquals(listOf(created.id, updated.id), graphStore.removed)
        assertEquals("fact_bot-a_group-a_${created.id}", created.vectorId)
        assertEquals("fact_bot-a_group-a_${updated.id}", updated.vectorId)
        assertEquals(listOf(created.id, updated.id), vectorStore.indexed)
        assertEquals(
            listOf(
                "fact_bot-a_group-a_${created.id}",
                "fact_bot-a_group-a_${updated.id}"
            ),
            vectorStore.deleted
        )
    }

    @Test
    fun `manual fact write does not persist vector id when vector indexing fails`() = runBlocking {
        createFactsDatabase()
        val repository = MemoryRepository()
        val vectorStore = RecordingVectorStoreFactory(failIndex = true)
        val graphStore = RecordingGraphStoreFactory()
        val service = MemoryService(
            memoryAgent = MemoryAgent(repository, vectorStore, graphStore, FailingPromptExecutor()),
            memoryRepository = repository,
            factVectorStoreFactory = vectorStore,
            factGraphStoreFactory = graphStore
        )

        val created = service.createFact("bot-a", "group-a", "new", "new fact", listOf("杭州"), "user-a", Scopes.USER)!!

        assertEquals(null, created.vectorId)
        assertEquals(null, repository.getFactById(created.id)!!.vectorId)
        assertEquals(listOf(created.id), graphStore.added)
    }

    @Test
    fun `manual fact update clears stale vector id when vector indexing fails`() = runBlocking {
        createFactsDatabase()
        val repository = MemoryRepository()
        val factId = repository.createFact("bot-a", "group-a", "old", "old fact", listOf("杭州"), "user-a", Scopes.USER)
        repository.updateFactVectorId(factId, "fact_bot-a_group-a_$factId")
        val vectorStore = RecordingVectorStoreFactory(failIndex = true)
        val graphStore = RecordingGraphStoreFactory()
        val service = MemoryService(
            memoryAgent = MemoryAgent(repository, vectorStore, graphStore, FailingPromptExecutor()),
            memoryRepository = repository,
            factVectorStoreFactory = vectorStore,
            factGraphStoreFactory = graphStore
        )

        val updated = service.updateFact(
            "bot-a",
            "group-a",
            factId,
            "updated",
            "updated fact",
            listOf("重庆"),
            "user-a",
            Scopes.USER
        )!!

        assertEquals(null, updated.vectorId)
        assertEquals(null, repository.getFactById(factId)!!.vectorId)
        assertEquals(listOf("fact_bot-a_group-a_$factId"), vectorStore.deleted)
        assertEquals(listOf(factId), graphStore.removed)
        assertEquals(listOf(factId), graphStore.added)
    }

    @Test
    fun `management vector search returns scores and fact records`() = runBlocking {
        createFactsDatabase()
        val repository = MemoryRepository()
        val factId = repository.createFact("bot-a", "group-a", "coffee", "likes light roast coffee", listOf("coffee"), "user-a", Scopes.USER)
        val vectorStore = RecordingVectorStoreFactory(
            listOf(FactSearchResult("fact_bot-a_group-a_$factId", factId, "coffee likes light roast coffee", 0.82f))
        )
        val graphStore = RecordingGraphStoreFactory()
        val service = MemoryService(
            memoryAgent = MemoryAgent(repository, vectorStore, graphStore, FailingPromptExecutor()),
            memoryRepository = repository,
            factVectorStoreFactory = vectorStore,
            factGraphStoreFactory = graphStore
        )

        val response = service.searchFactsVector("bot-a", "group-a", "light roast", limit = 5)

        assertEquals("light roast", response.query)
        assertEquals(1, response.results.size)
        assertEquals(factId, response.results.single().fact.id)
        assertEquals(0.82f, response.results.single().score)
    }

    @Test
    fun `management vector search combines bm25 and vector results sorted by score`() = runBlocking {
        createFactsDatabase()
        val repository = MemoryRepository()
        val bm25Only = repository.createFact("bot-a", "group-a", "bm25", "bm25 fact", listOf("coffee"), "user-a", Scopes.USER)
        val both = repository.createFact("bot-a", "group-a", "both", "both fact", listOf("coffee"), "user-a", Scopes.USER)
        val vectorOnly = repository.createFact("bot-a", "group-a", "vector", "vector fact", listOf("coffee"), "user-a", Scopes.USER)
        val vectorStore = RecordingVectorStoreFactory(
            searchResults = listOf(
                FactSearchResult("fact_bot-a_group-a_$both", both, "both fact", 0.90f),
                FactSearchResult("fact_bot-a_group-a_$vectorOnly", vectorOnly, "vector fact", 0.70f)
            ),
            keywordResults = listOf(
                FactSearchResult("fact_bot-a_group-a_$bm25Only", bm25Only, "bm25 fact", 0.80f),
                FactSearchResult("fact_bot-a_group-a_$both", both, "both fact", 0.60f)
            )
        )
        val service = MemoryService(
            memoryAgent = MemoryAgent(repository, vectorStore, RecordingGraphStoreFactory(), FailingPromptExecutor()),
            memoryRepository = repository,
            factVectorStoreFactory = vectorStore,
            factGraphStoreFactory = RecordingGraphStoreFactory()
        )

        val response = service.searchFactsVector("bot-a", "group-a", "coffee", limit = 10)

        assertEquals(listOf(both, bm25Only, vectorOnly), response.results.map { it.fact.id })
        assertEquals(listOf("vector", "bm25", "vector"), response.results.map { it.source })
        assertEquals(listOf(0.90f, 0.80f, 0.70f), response.results.map { it.score })
    }

    @Test
    fun `management vector search returns bm25 results when embedding search fails`() = runBlocking {
        createFactsDatabase()
        val repository = MemoryRepository()
        val bm25Only = repository.createFact("bot-a", "group-a", "bm25", "bm25 fact", listOf("coffee"), "user-a", Scopes.USER)
        val vectorStore = RecordingVectorStoreFactory(
            keywordResults = listOf(FactSearchResult("fact_bot-a_group-a_$bm25Only", bm25Only, "bm25 fact", 0.80f)),
            failSearch = true
        )
        val service = MemoryService(
            memoryAgent = MemoryAgent(repository, vectorStore, RecordingGraphStoreFactory(), FailingPromptExecutor()),
            memoryRepository = repository,
            factVectorStoreFactory = vectorStore,
            factGraphStoreFactory = RecordingGraphStoreFactory()
        )

        val response = service.searchFactsVector("bot-a", "group-a", "coffee", limit = 10)

        assertEquals(listOf(bm25Only), response.results.map { it.fact.id })
        assertEquals(listOf("bm25"), response.results.map { it.source })
    }

    @Test
    fun `fact keyword search text includes keyword description entities and subjects`() {
        createFactsDatabase()
        val repository = MemoryRepository()
        val factId = repository.createFact(
            "bot-a",
            "group-a",
            "coffee",
            "likes light roast coffee",
            listOf("coffee", "cafe"),
            "user-a",
            Scopes.USER
        )
        val fact = repository.getFactById(factId)!!

        assertEquals("coffee likes light roast coffee coffee cafe user-a", buildFactKeywordSearchText(fact))
    }

    @Test
    fun `rebuild fact vectors clears group stores reindexes valid facts and updates vector ids`() = runBlocking {
        createFactsDatabase()
        val repository = MemoryRepository()
        val first = repository.createFact("bot-a", "group-a", "first", "first fact", listOf("杭州"), "user-a", Scopes.USER)
        val second = repository.createFact("bot-a", "group-b", "second", "second fact", listOf("上海"), "user-b", Scopes.USER)
        repository.createFact("bot-b", "group-a", "third", "third fact", listOf("重庆"), "user-c", Scopes.USER)
        repository.deprecateFactsById("bot-a", "group-a", first, Scopes.USER)
        val vectorStore = RecordingVectorStoreFactory()
        val graphStore = RecordingGraphStoreFactory()
        val service = MemoryService(
            memoryAgent = MemoryAgent(repository, vectorStore, graphStore, FailingPromptExecutor()),
            memoryRepository = repository,
            factVectorStoreFactory = vectorStore,
            factGraphStoreFactory = graphStore
        )

        val result = service.rebuildFactVectors()

        assertEquals(2, result.facts)
        assertEquals(setOf("bot-a:group-a", "bot-a:group-b", "bot-b:group-a"), result.groups.toSet())
        assertEquals(
            mapOf(
                "bot-a:group-a" to emptyList(),
                "bot-a:group-b" to listOf(second),
                "bot-b:group-a" to listOf(3)
            ),
            vectorStore.rebuilt
        )
        assertEquals(
            listOf("bot-a:group-a", "bot-a:group-b", "bot-b:group-a"),
            vectorStore.activeGroups.map { (botMark, groupId) -> "$botMark:$groupId" }
        )
        assertEquals("fact_bot-a_group-b_$second", repository.getFactById(second)!!.vectorId)
    }

    @Test
    fun `fact vector rebuild rejects embedding count mismatch`() {
        createFactsDatabase()
        val repository = MemoryRepository()
        repository.createFact("bot-a", "group-a", "first", "first fact", listOf("杭州"), "user-a", Scopes.USER)
        repository.createFact("bot-a", "group-a", "second", "second fact", listOf("上海"), "user-b", Scopes.USER)
        val facts = repository.getValidFacts("bot-a", "group-a").sortedBy { it.id }

        val error = assertFailsWith<IllegalStateException> {
            pairFactsWithVectorsForRebuild(facts, listOf(FloatArray(1024)))
        }

        assertTrue(error.message!!.contains("Expected 2 embedding vectors but got 1"))
    }

    @Test
    fun `rebuild fact graphs rebuilds each valid fact group once`() {
        createFactsDatabase()
        val repository = MemoryRepository()
        val first = repository.createFact("bot-a", "group-a", "first", "first fact", listOf("杭州"), "user-a", Scopes.USER)
        repository.createFact("bot-a", "group-b", "second", "second fact", listOf("上海"), "user-b", Scopes.USER)
        repository.createFact("bot-b", "group-a", "third", "third fact", listOf("重庆"), "user-c", Scopes.USER)
        repository.deprecateFactsById("bot-a", "group-a", first, Scopes.USER)
        val vectorStore = RecordingVectorStoreFactory()
        val graphStore = RecordingGraphStoreFactory()
        val service = MemoryService(
            memoryAgent = MemoryAgent(repository, vectorStore, graphStore, FailingPromptExecutor()),
            memoryRepository = repository,
            factVectorStoreFactory = vectorStore,
            factGraphStoreFactory = graphStore
        )

        val result = service.rebuildFactGraphs()

        assertEquals(2, result.facts)
        assertEquals(setOf("bot-a:group-a", "bot-a:group-b", "bot-b:group-a"), result.groups.toSet())
        assertEquals(listOf("bot-a:group-a", "bot-a:group-b", "bot-b:group-a"), graphStore.rebuilt)
        assertEquals(
            listOf("bot-a:group-a", "bot-a:group-b", "bot-b:group-a"),
            graphStore.activeGroups.map { (botMark, groupId) -> "$botMark:$groupId" }
        )
    }

    @Test
    fun `memory rebuild options read environment and system properties`() {
        val defaults = MemoryRebuildOptions.from(emptyMap()) { null }
        val envOnly = MemoryRebuildOptions.from(
            mapOf(
                "MEMORY_REBUILD_ENTITIES" to "yes",
                "MEMORY_REBUILD_VECTOR" to "true",
                "MEMORY_REBUILD_GRAPH" to "1"
            )
        ) { null }
        val propertyOverridesEnv = MemoryRebuildOptions.from(
            mapOf(
                "MEMORY_REBUILD_ENTITIES" to "true",
                "MEMORY_REBUILD_VECTOR" to "true",
                "MEMORY_REBUILD_GRAPH" to "true"
            )
        ) { key ->
            when (key) {
                "memory.rebuild.entities" -> "off"
                "memory.rebuild.vector" -> "false"
                "memory.rebuild.graph" -> "0"
                else -> null
            }
        }
        val rebuildAll = MemoryRebuildOptions.from(mapOf("MEMORY_REBUILD" to "on")) { null }

        assertEquals(MemoryRebuildOptions(), defaults)
        assertEquals(MemoryRebuildOptions(entities = true, vector = true, graph = true), envOnly)
        assertEquals(MemoryRebuildOptions(entities = false, vector = false, graph = false), propertyOverridesEnv)
        assertEquals(MemoryRebuildOptions(entities = true, vector = true, graph = true), rebuildAll)
    }

    @Test
    fun `configured rebuild continues graph when vector fails`() = runBlocking {
        val events = mutableListOf<String>()
        val failures = mutableListOf<String>()

        runConfiguredMemoryRebuilds(
            options = MemoryRebuildOptions(vector = true, graph = true),
            rebuildVector = {
                events += "vector"
                throw IllegalStateException("missing embedding model")
            },
            rebuildGraph = {
                events += "graph"
            },
            logFailure = { name, error ->
                failures += "$name:${error.message}"
            }
        )

        assertEquals(listOf("vector", "graph"), events)
        assertEquals(listOf("vector:missing embedding model"), failures)
    }

    @Test
    fun `entity rebuild also refreshes dependent vector and graph stores`() = runBlocking {
        val events = mutableListOf<String>()

        runConfiguredMemoryRebuilds(
            options = MemoryRebuildOptions(entities = true),
            rebuildEntities = { events += "entities" },
            rebuildVector = { events += "vector" },
            rebuildGraph = { events += "graph" },
            logFailure = { _, error -> throw error }
        )

        assertEquals(listOf("entities", "vector", "graph"), events)
    }

    @Test
    fun `fact entity rebuild only analyzes empty valid facts`() = runBlocking {
        createFactsDatabase()
        val repository = MemoryRepository()
        val emptyValid = repository.createFact("bot-a", "group-a", "move", "user moved from 杭州 to 重庆", emptyList(), "user-a", Scopes.USER)
        repository.createFact("bot-a", "group-a", "company", "user works at 字节跳动", listOf("字节跳动"), "user-a", Scopes.USER)
        val invalid = repository.createFact("bot-a", "group-a", "old", "old 上海 fact", emptyList(), "user-a", Scopes.USER)
        repository.deprecateFactsById("bot-a", "group-a", invalid, Scopes.USER)
        val runner = FactEntityRebuildRunner(repository) { fact ->
            when (fact.id) {
                emptyValid -> listOf("杭州", "重庆")
                else -> error("Unexpected fact analyzed: ${fact.id}")
            }
        }

        val report = runner.run()

        assertEquals(FactEntityRebuildSummary(scanned = 1, updated = 1, unchanged = 0, failed = 0), report.summary)
        assertEquals(listOf("杭州", "重庆"), repository.getFactById(emptyValid)!!.entities)
        assertEquals(listOf("杭州", "重庆"), report.items.single().after)
    }

    @Test
    fun `fact entity rebuild updates normalized entities`() = runBlocking {
        createFactsDatabase()
        val repository = MemoryRepository()
        val factId = repository.createFact("bot-a", "group-a", "move", "user moved from 杭州 to 重庆", emptyList(), "user-a", Scopes.USER)
        val runner = FactEntityRebuildRunner(repository) {
            listOf(" 杭州 ", "重庆", "杭州", "")
        }

        val report = runner.run()

        assertEquals(FactEntityRebuildSummary(scanned = 1, updated = 1, unchanged = 0, failed = 0), report.summary)
        assertEquals(listOf("杭州", "重庆"), repository.getFactById(factId)!!.entities)
        assertEquals(listOf("杭州", "重庆"), report.items.single().after)
    }

    @Test
    fun `entity candidates use lucene tokenization and stop words`() {
        val candidates = extractEntityCandidates("the user moved from 杭州 to 重庆 and the user stayed in 杭州")

        assertTrue("杭州" in candidates)
        assertTrue("重庆" in candidates)
        assertFalse("the" in candidates)
        assertFalse("and" in candidates)
        assertFalse("to" in candidates)
        assertEquals(candidates.distinct(), candidates)
        assertTrue(candidates.size <= 16)
    }

    @Test
    fun `management graph search returns seed expanded nodes and edges inside current bot group`() = runBlocking {
        createFactsDatabase()
        val repository = MemoryRepository()
        val seed = repository.createFact("bot-a", "group-a", "seed", "seed fact", listOf("杭州"), "user-a", Scopes.USER)
        val expanded = repository.createFact("bot-a", "group-a", "expanded", "expanded fact", listOf("杭州", "西湖"), "user-a", Scopes.USER)
        val otherGroup = repository.createFact("bot-a", "group-b", "other group", "other group fact", listOf("杭州"), "user-a", Scopes.USER)
        val vectorStore = RecordingVectorStoreFactory(
            listOf(FactSearchResult("fact_bot-a_group-a_$seed", seed, "seed fact", 0.91f))
        )
        val graphStore = RecordingGraphStoreFactory(
            factsToEntities = mapOf(seed to listOf("杭州")),
            entitiesToFacts = mapOf("杭州" to listOf(seed, expanded, otherGroup))
        )
        val service = MemoryService(
            memoryAgent = MemoryAgent(repository, vectorStore, graphStore, FailingPromptExecutor()),
            memoryRepository = repository,
            factVectorStoreFactory = vectorStore,
            factGraphStoreFactory = graphStore
        )

        val response = service.searchFactsGraph("bot-a", "group-a", "杭州", limit = 5)

        assertEquals(listOf(seed), response.seedResults.map { it.fact.id })
        assertEquals(listOf(expanded), response.expandedResults.map { it.fact.id })
        assertTrue(response.nodes.any { it.id == "fact:$seed" && it.type == "fact" && it.source == "seed" })
        assertTrue(response.nodes.any { it.id == "fact:$expanded" && it.type == "fact" && it.source == "expanded" })
        assertTrue(response.nodes.any { it.id == "entity:杭州" && it.type == "entity" })
        assertTrue(response.edges.any { it.from == "fact:$seed" && it.to == "entity:杭州" && it.label == "involves" })
    }

    @Test
    fun `management graph search limits expanded facts`() = runBlocking {
        createFactsDatabase()
        val repository = MemoryRepository()
        val seed = repository.createFact("bot-a", "group-a", "seed", "seed fact", listOf("杭州"), "user-a", Scopes.USER)
        val expandedFirst = repository.createFact("bot-a", "group-a", "expanded first", "expanded first fact", listOf("杭州"), "user-a", Scopes.USER)
        val expandedSecond = repository.createFact("bot-a", "group-a", "expanded second", "expanded second fact", listOf("杭州"), "user-a", Scopes.USER)
        val vectorStore = RecordingVectorStoreFactory(
            searchResults = listOf(FactSearchResult("fact_bot-a_group-a_$seed", seed, "seed fact", 0.91f))
        )
        val graphStore = RecordingGraphStoreFactory(
            factsToEntities = mapOf(seed to listOf("杭州")),
            entitiesToFacts = mapOf("杭州" to listOf(seed, expandedFirst, expandedSecond))
        )
        val service = MemoryService(
            memoryAgent = MemoryAgent(repository, vectorStore, graphStore, FailingPromptExecutor()),
            memoryRepository = repository,
            factVectorStoreFactory = vectorStore,
            factGraphStoreFactory = graphStore
        )

        val response = service.searchFactsGraph("bot-a", "group-a", "杭州", limit = 1)

        assertEquals(listOf(expandedFirst), response.expandedResults.map { it.fact.id })
    }

    private fun createFactsDatabase(): Database {
        val database = Database.connect(
            url = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        TransactionManager.defaultDatabase = database
        transaction(database) {
            SchemaUtils.create(FactsTable)
        }
        return database
    }

    private fun createLegacyFactsDatabase(): Database {
        val database = Database.connect(
            url = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        TransactionManager.defaultDatabase = database
        transaction(database) {
            exec(
                """
                CREATE TABLE memory_facts (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    bot_mark VARCHAR(255) NOT NULL,
                    group_id VARCHAR(255) NOT NULL,
                    keyword VARCHAR(255) NOT NULL,
                    description TEXT NOT NULL,
                    "values" TEXT NOT NULL,
                    subjects TEXT NOT NULL,
                    scope_type VARCHAR(50) NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    valid_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    valid_to TIMESTAMP NULL,
                    last_recalled_at TIMESTAMP NULL,
                    vector_id VARCHAR(64) NULL
                )
                """.trimIndent()
            )
            exec(
                """
                INSERT INTO memory_facts
                    (bot_mark, group_id, keyword, description, "values", subjects, scope_type)
                VALUES
                    ('bot-a', 'group-a', 'legacy', 'legacy fact', 'legacy-value', 'user-a', 'USER')
                """.trimIndent()
            )
        }
        return database
    }

    private class RecordingVectorStoreFactory(
        private val searchResults: List<FactSearchResult> = emptyList(),
        private val keywordResults: List<FactSearchResult> = emptyList(),
        private val failIndex: Boolean = false,
        private val failSearch: Boolean = false
    ) : FactVectorStoreFactory() {
        val cleared = mutableListOf<String>()
        val indexed = mutableListOf<Int>()
        val deleted = mutableListOf<String>()
        val rebuilt = linkedMapOf<String, List<Int>>()
        var activeGroups: List<Pair<String, String>> = emptyList()
        val searchTopKs = mutableListOf<Int>()
        val keywordTopKs = mutableListOf<Int>()

        override suspend fun search(
            query: String,
            groupId: String,
            botMark: String,
            topK: Int
        ): List<FactSearchResult> {
            searchTopKs.add(topK)
            if (failSearch) {
                error("expected vector search failure")
            }
            return searchResults.take(topK)
        }

        override fun searchByKeyword(
            query: String,
            groupId: String,
            botMark: String,
            topK: Int
        ): List<FactSearchResult> {
            keywordTopKs.add(topK)
            return keywordResults.take(topK)
        }

        override fun clearStore(botMark: String, groupId: String) {
            cleared.add("$botMark:$groupId")
        }

        override suspend fun rebuildStore(botMark: String, groupId: String, facts: List<FactsRecord>): List<Pair<Int, String>> {
            rebuilt["$botMark:$groupId"] = facts.map { it.id }
            return facts.map { it.id to generateVectorId(it.botMark, it.groupId, it.id) }
        }

        override fun removeOrphanStores(activeGroups: List<Pair<String, String>>): List<String> {
            this.activeGroups = activeGroups
            return emptyList()
        }

        override suspend fun indexFact(fact: FactsRecord): String {
            if (failIndex) {
                error("expected vector indexing failure")
            }
            indexed.add(fact.id)
            return generateVectorId(fact.botMark, fact.groupId, fact.id)
        }

        override fun deleteVector(vectorId: String, botMark: String, groupId: String) {
            deleted.add(vectorId)
        }
    }

    private class RecordingGraphStoreFactory(
        private val factsToEntities: Map<Int, List<String>> = emptyMap(),
        private val entitiesToFacts: Map<String, List<Int>> = emptyMap()
    ) : FactGraphStoreFactory() {
        val added = mutableListOf<Int>()
        val removed = mutableListOf<Int>()
        val rebuilt = mutableListOf<String>()
        var activeGroups: List<Pair<String, String>> = emptyList()

        override fun rebuildStore(botMark: String, groupId: String) {
            rebuilt.add("$botMark:$groupId")
        }

        override fun removeOrphanStores(activeGroups: List<Pair<String, String>>): List<String> {
            this.activeGroups = activeGroups
            return emptyList()
        }

        override fun addFactEntities(fact: FactsRecord) {
            added.add(fact.id)
        }

        override fun removeFactEntities(factId: Int, botMark: String, groupId: String) {
            removed.add(factId)
        }

        override fun expandByEntities(entityIds: List<String>, botMark: String, groupId: String): List<Int> =
            entityIds.flatMap { entitiesToFacts[it].orEmpty() }

        override fun expandByFacts(factIds: List<Int>, botMark: String, groupId: String): List<String> =
            factIds.flatMap { factsToEntities[it].orEmpty() }
    }

    private class FailingPromptExecutor : PromptExecutor() {
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            error("Prompt execution should not be used in this test")
        }

        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> {
            error("Prompt streaming should not be used in this test")
        }

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult {
            error("Moderation should not be used in this test")
        }

        override fun close() = Unit
    }
}
