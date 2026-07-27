package uesugi.core.state.meme

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import org.koin.core.parameter.parametersOf
import uesugi.common.extend.EmbeddingInput
import uesugi.config.StorePathConfig
import uesugi.core.component.embedding.EmbeddingManager
import uesugi.core.component.storage.VectorStore
import uesugi.core.component.storage.VectorStoreItem
import uesugi.core.state.meme.MemeData.MemeRecord

/**
 * 表情包向量存储工厂
 */
open class MemoVectorStore {
    companion object {
        private const val DIMENSION = 1024
        private const val REBUILD_EMBEDDING_BATCH_SIZE = 64
    }

    private val stores = mutableMapOf<String, VectorStore>()

    /**
     * 获取指定 botId 和 groupId 的向量存储
     */
    fun getStore(botMark: String, groupId: String): VectorStore {
        val key = "${botMark}_$groupId"
        return stores.getOrPut(key) {
            val path = StorePathConfig.resolve("vector", "meme", key)
            GlobalContext.get().get { parametersOf(path, DIMENSION) }
        }
    }

    /**
     * 将文本编码为向量
     */
    suspend fun encode(text: String, image: ByteArray? = null): FloatArray {
        val images = if (image != null) listOf(image) else emptyList()
        return try {
            EmbeddingManager.get().embeddingMultiModal(listOf(EmbeddingInput(text, images))).first()
        } catch (_: Exception) {
            EmbeddingManager.get().embedding(listOf(text)).first()
        }
    }

    open suspend fun rebuildStore(
        botMark: String,
        groupId: String,
        memos: List<MemeRecord>
    ): List<Pair<Int, String>> {
        val indexedMemos = memos.mapNotNull { memo ->
            val id = memo.id ?: return@mapNotNull null
            val content = buildMemeVectorContent(memo).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Triple(memo, id, content)
        }.sortedBy { it.second }

        if (indexedMemos.isEmpty()) {
            getStore(botMark, groupId).rebuild(emptyList())
            return emptyList()
        }

        val vectors = withContext(Dispatchers.IO) {
            indexedMemos.chunked(REBUILD_EMBEDDING_BATCH_SIZE).flatMap { batch ->
                EmbeddingManager.get().embedding(batch.map { it.third })
            }
        }
        check(vectors.size == indexedMemos.size) {
            "Expected ${indexedMemos.size} meme embedding vectors but got ${vectors.size}"
        }

        val items = indexedMemos.zip(vectors).map { (indexed, vector) ->
            val (memo, id, content) = indexed
            VectorStoreItem(
                id = generateVectorId(memo.botId, memo.groupId, id),
                content = content,
                tag = "",
                vector = vector
            )
        }
        getStore(botMark, groupId).rebuild(items)
        return indexedMemos.zip(items).map { (indexed, item) -> indexed.second to item.id }
    }

    /**
     * 生成向量ID
     */
    fun generateVectorId(botMark: String, groupId: String, memoId: Int): String {
        return "memo_${botMark}_${groupId}_$memoId"
    }

    /**
     * 从向量ID中提取memoId
     * 向量ID格式: memo_{botId}_{groupId}_{memoId}
     */
    fun extractMemoId(vectorId: String): Int? {
        return try {
            // 从最后一个_之后获取数字
            vectorId.substringAfterLast("_").toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }

}

internal fun buildMemeVectorContent(memo: MemeRecord): String = buildString {
    memo.purpose?.takeIf { it.isNotBlank() }?.let { append(it) }
    memo.tags?.takeIf { it.isNotBlank() }?.let {
        if (isNotEmpty()) append(' ')
        append(it)
    }
}
