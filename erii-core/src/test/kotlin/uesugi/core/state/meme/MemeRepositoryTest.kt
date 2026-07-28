package uesugi.core.state.meme

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlinx.coroutines.runBlocking
import uesugi.common.data.HistoryEntity
import uesugi.common.data.HistoryTable
import uesugi.common.data.MessageType
import uesugi.common.data.ResourceEntity
import uesugi.common.data.ResourceTable
import uesugi.core.state.meme.MemeData.MemeRecord
import uesugi.core.state.meme.MemeData.MemeTable
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class MemeRepositoryTest {
    @Test
    fun `rebuild vector stores reindexes analyzed memes and clears groups without analyzed memes`() = runBlocking {
        createDatabase()
        val repository = MemeRepository()
        val analyzed = repository.addOrUpdateMeme("bot-a", "group-a", 1, "analyzed", null)
        repository.updateAnalysis(
            memeId = analyzed.id!!,
            description = "description",
            purpose = "reply",
            tags = "cat,funny",
            vectorId = "old-vector",
            analyzedCount = 3
        )
        repository.addOrUpdateMeme("bot-a", "group-b", 2, "pending", null)
        val vectorStore = RecordingMemoVectorStore()
        val service = MemeService(vectorStore, repository)

        val result = service.rebuildVectorStores()

        assertEquals(1, result.memes)
        assertEquals(listOf("bot-a:group-a", "bot-a:group-b"), result.groups)
        assertEquals(
            mapOf(
                "bot-a:group-a" to listOf(analyzed.id),
                "bot-a:group-b" to emptyList()
            ),
            vectorStore.rebuilt
        )
        assertEquals("rebuilt-${analyzed.id}", repository.getMemoById(analyzed.id)!!.vectorId)
    }

    @Test
    fun `meme rebuild option accepts property and environment flags`() {
        assertEquals(
            MemeRebuildOptions(vector = true),
            MemeRebuildOptions.from(env = mapOf("MEME_REBUILD_VECTOR" to "yes"), property = { null })
        )
        assertEquals(
            MemeRebuildOptions(vector = false),
            MemeRebuildOptions.from(env = mapOf("MEME_REBUILD_VECTOR" to "yes"), property = { "false" })
        )
    }

    @Test
    fun `recent image messages skip image history without resource`() {
        val database = createDatabase()
        transaction(database) {
            HistoryEntity.new {
                botMark = "bot-a"
                groupId = "group-a"
                userId = "user-a"
                nick = "user"
                messageType = MessageType.IMAGE
                content = "image without stored resource"
            }
            val resource = ResourceEntity.new {
                botMark = "bot-a"
                groupId = "group-a"
                url = "images/ok.png"
                fileName = "ok.png"
                size = 42
                md5 = "md5-ok"
            }
            HistoryEntity.new {
                botMark = "bot-a"
                groupId = "group-a"
                userId = "user-a"
                nick = "user"
                messageType = MessageType.IMAGE
                content = "image with resource"
                this.resource = resource
            }
        }

        val images = MemeRepository().getRecentImageMessages("bot-a", "group-a")

        assertEquals(1, images.size)
        assertEquals("image with resource", images.single().content)
        assertEquals("md5-ok", images.single().md5)
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

    private class RecordingMemoVectorStore : MemoVectorStore() {
        val rebuilt = linkedMapOf<String, List<Int?>>()

        override suspend fun rebuildStore(
            botMark: String,
            groupId: String,
            memos: List<MemeRecord>
        ): List<Pair<Int, String>> {
            rebuilt["$botMark:$groupId"] = memos.map { it.id }
            return memos.map { meme -> meme.id!! to "rebuilt-${meme.id}" }
        }
    }
}
