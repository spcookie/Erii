package uesugi.core.state.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.core.context.GlobalContext
import org.koin.core.parameter.parametersOf
import uesugi.config.StorePathConfig
import uesugi.core.component.embedding.EmbeddingManager
import uesugi.core.component.storage.VectorStore
import uesugi.core.component.storage.VectorStoreItem

internal fun pairFactsWithVectorsForRebuild(
    orderedFacts: List<FactsRecord>,
    vectors: List<FloatArray>
): List<Pair<FactsRecord, FloatArray>> {
    check(orderedFacts.size == vectors.size) {
        "Expected ${orderedFacts.size} embedding vectors but got ${vectors.size}"
    }
    return orderedFacts.zip(vectors)
}

internal fun buildFactKeywordSearchText(fact: FactsRecord): String {
    return listOf(
        fact.keyword,
        fact.description,
        fact.entities.joinToString(" "),
        fact.subjects
    )
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
}

/**
 * 事实向量存储工厂
 */
open class FactVectorStoreFactory {
    companion object {
        private const val DIMENSION = 1024
        private const val REBUILD_EMBEDDING_BATCH_SIZE = 64
    }

    private val stores = mutableMapOf<String, VectorStore>()

    /**
     * 获取指定 botId 和 groupId 的向量存储
     */
    open fun getStore(botMark: String, groupId: String): VectorStore {
        val key = "${botMark}_$groupId"
        return stores.getOrPut(key) {
            val path = StorePathConfig.resolve("vector", "fact", key)
            GlobalContext.get().get { parametersOf(path, DIMENSION) }
        }
    }

    /**
     * 索引事实记忆
     * @return 向量ID
     */
    open suspend fun indexFact(fact: FactsRecord): String {
        val botMark = fact.botMark
        val groupId = fact.groupId
        val factId = fact.id

        val vectorId = generateVectorId(botMark, groupId, factId)

        val content = fact.description

        val vector: FloatArray = withContext(Dispatchers.IO) {
            EmbeddingManager.get().embedding(listOf(content)).first()
        }
        val store = getStore(botMark, groupId)
        store.upsert(vectorId, content, fact.scopeType.name, vector, buildFactKeywordSearchText(fact))

        return vectorId
    }

    /**
     * 搜索事实记忆
     */
    open suspend fun search(
        query: String,
        groupId: String,
        botMark: String,
        topK: Int
    ): List<FactSearchResult> {
        val vector: FloatArray = withContext(Dispatchers.IO) {
            EmbeddingManager.get().embedding(listOf(query)).first()
        }
        val store = getStore(botMark, groupId)
        val results = store.search(vector, topK)

        return results.map { result ->
            val factId = extractFactId(result.id)
            FactSearchResult(
                vectorId = result.id,
                factId = factId,
                content = result.content,
                score = result.score
            )
        }
    }

    /**
     * 删除向量
     */
    open fun deleteVector(vectorId: String, botMark: String, groupId: String) {
        val store = getStore(botMark, groupId)
        store.delete(vectorId)
    }

    open fun clearStore(botMark: String, groupId: String) {
        getStore(botMark, groupId).deleteAll()
    }

    open suspend fun rebuildStore(botMark: String, groupId: String, facts: List<FactsRecord>): List<Pair<Int, String>> {
        if (facts.isEmpty()) {
            getStore(botMark, groupId).rebuild(emptyList())
            return emptyList()
        }

        val orderedFacts = facts.sortedBy { it.id }
        val contents = orderedFacts.map { it.description }
        val vectors = withContext(Dispatchers.IO) {
            contents.chunked(REBUILD_EMBEDDING_BATCH_SIZE).flatMap { batch ->
                EmbeddingManager.get().embedding(batch)
            }
        }
        val items = pairFactsWithVectorsForRebuild(orderedFacts, vectors).map { (fact, vector) ->
            VectorStoreItem(
                id = generateVectorId(fact.botMark, fact.groupId, fact.id),
                content = fact.description,
                tag = fact.scopeType.name,
                vector = vector,
                searchText = buildFactKeywordSearchText(fact)
            )
        }

        getStore(botMark, groupId).rebuild(items)
        return orderedFacts.zip(items).map { (fact, item) -> fact.id to item.id }
    }

    open fun removeOrphanStores(activeGroups: List<Pair<String, String>>): List<String> {
        val root = StorePathConfig.resolve("vector", "fact")
        val activeKeys = activeGroups.mapTo(hashSetOf()) { (botMark, groupId) -> "${botMark}_$groupId" }
        return removeOrphanStoreDirectories(root, activeKeys) { key ->
            stores.remove(key)?.close()
        }
    }

    /**
     * 生成向量ID
     */
    open fun generateVectorId(botMark: String, groupId: String, factId: Int): String {
        return "fact_${botMark}_${groupId}_$factId"
    }

    /**
     * BM25 关键词搜索（不依赖向量）
     */
    open fun searchByKeyword(
        query: String,
        groupId: String,
        botMark: String,
        topK: Int
    ): List<FactSearchResult> {
        val store = getStore(botMark, groupId)
        val results = store.searchText(query, topK)

        return results.map { result ->
            FactSearchResult(
                vectorId = result.id,
                factId = extractFactId(result.id),
                content = result.content,
                score = result.score
            )
        }
    }

    /**
     * 从向量ID中提取 factId
     * 向量ID格式: fact_{botId}_{groupId}_{factId}
     */
    open fun extractFactId(vectorId: String): Int? {
        return try {
            vectorId.substringAfterLast("_").toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }

}

/**
 * 事实搜索结果
 */
@Serializable
data class FactSearchResult(
    val vectorId: String,
    val factId: Int?,
    val content: String,
    val score: Float
)
