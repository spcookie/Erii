package uesugi.core.component.storage

import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.document.*
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.search.*
import org.apache.lucene.store.FSDirectory
import java.io.Closeable
import java.io.StringReader
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

data class VectorSearchResult(
    val id: String,
    val content: String,
    val tag: String,
    val score: Float
)

data class VectorStoreItem(
    val id: String,
    val content: String,
    val tag: String,
    val vector: FloatArray,
    val searchText: String = content
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VectorStoreItem) return false

        if (id != other.id) return false
        if (content != other.content) return false
        if (tag != other.tag) return false
        if (!vector.contentEquals(other.vector)) return false
        if (searchText != other.searchText) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + tag.hashCode()
        result = 31 * result + vector.contentHashCode()
        result = 31 * result + searchText.hashCode()
        return result
    }
}

interface VectorStore {
    fun upsert(id: String, content: String, tag: String, vector: FloatArray, searchText: String = content)
    fun delete(id: String)
    fun deleteAll()
    fun rebuild(items: List<VectorStoreItem>)
    fun search(queryVector: FloatArray, topK: Int, filter: List<String>? = null): List<VectorSearchResult>
    fun searchText(query: String, topK: Int): List<VectorSearchResult>
}

class EmbeddedVectorStore(
    path: Path,
    private val dimension: Int
) : VectorStore, Closeable {

    private val directory = FSDirectory.open(path)

    private val writer: IndexWriter
    private val searcherManager: SearcherManager

    private val lock = ReentrantReadWriteLock()

    @Volatile
    private var uncommittedCount = 0

    companion object {
        const val VECTOR_FIELD = "vector"
        const val ID_FIELD = "id"
        const val CONTENT_FIELD = "content"
        const val SEARCH_TEXT_FIELD = "search_text"
        const val TAG = "tag"

        private const val COMMIT_THRESHOLD = 50
    }

    init {
        val config = IndexWriterConfig(SmartChineseAnalyzer()).apply {
            openMode = IndexWriterConfig.OpenMode.CREATE_OR_APPEND
        }

        writer = IndexWriter(directory, config)
        searcherManager = SearcherManager(writer, null)
    }

    override fun upsert(id: String, content: String, tag: String, vector: FloatArray, searchText: String) {
        validate(vector)

        lock.write {
            writer.updateDocument(Term(ID_FIELD, id), document(id, content, tag, vector, searchText))
            searcherManager.maybeRefresh()
            maybeCommit()
        }
    }

    override fun delete(id: String) {
        lock.write {
            writer.deleteDocuments(Term(ID_FIELD, id))
            searcherManager.maybeRefresh()
            maybeCommit()
        }
    }

    override fun deleteAll() {
        lock.write {
            writer.deleteAll()
            writer.commit()
            uncommittedCount = 0
            searcherManager.maybeRefresh()
        }
    }

    override fun rebuild(items: List<VectorStoreItem>) {
        items.forEach { validate(it.vector) }

        lock.write {
            writer.deleteAll()
            items.forEach { item ->
                writer.addDocument(document(item.id, item.content, item.tag, item.vector, item.searchText))
            }
            writer.commit()
            uncommittedCount = 0
            searcherManager.maybeRefresh()
        }
    }

    override fun search(
        queryVector: FloatArray,
        topK: Int,
        filter: List<String>?
    ): List<VectorSearchResult> {

        validate(queryVector)

        lock.read {
            val searcher = searcherManager.acquire()
            try {

                val knnQuery = KnnFloatVectorQuery(VECTOR_FIELD, queryVector, topK)

                val finalQuery = if (filter.isNullOrEmpty()) {
                    knnQuery
                } else {
                    buildFilteredQuery(knnQuery, filter)
                }

                val topDocs = searcher.search(finalQuery, topK)

                val storedFields = searcher.storedFields()

                return topDocs.scoreDocs.map {
                    val doc = storedFields.document(it.doc)
                    VectorSearchResult(
                        id = doc.get(ID_FIELD),
                        content = doc.get(CONTENT_FIELD),
                        tag = doc.get(TAG),
                        score = it.score
                    )
                }
            } finally {
                searcherManager.release(searcher)
            }
        }
    }

    override fun searchText(query: String, topK: Int): List<VectorSearchResult> {
        lock.read {
            val searcher = searcherManager.acquire()
            try {
                val builder = BooleanQuery.Builder()
                val analyzer = SmartChineseAnalyzer()
                val tokenStream = analyzer.tokenStream(SEARCH_TEXT_FIELD, StringReader(query))
                val charTermAttr = tokenStream.addAttribute(CharTermAttribute::class.java)
                tokenStream.reset()
                while (tokenStream.incrementToken()) {
                    val token = charTermAttr.toString()
                    if (token.length >= 2) {
                        builder.add(TermQuery(Term(SEARCH_TEXT_FIELD, token)), BooleanClause.Occur.SHOULD)
                    }
                }
                tokenStream.end()
                tokenStream.close()
                analyzer.close()

                val topDocs = searcher.search(builder.build(), topK)
                val storedFields = searcher.storedFields()

                return topDocs.scoreDocs.map {
                    val doc = storedFields.document(it.doc)
                    VectorSearchResult(
                        id = doc.get(ID_FIELD),
                        content = doc.get(CONTENT_FIELD),
                        tag = doc.get(TAG),
                        score = it.score
                    )
                }
            } finally {
                searcherManager.release(searcher)
            }
        }
    }

    override fun close() {
        lock.write {
            flush()
            searcherManager.close()
            writer.close()
            directory.close()
        }
    }

    fun flush() {
        writer.commit()
        uncommittedCount = 0
    }

    private fun maybeCommit() {
        uncommittedCount++
        if (uncommittedCount >= COMMIT_THRESHOLD) {
            writer.commit()
            uncommittedCount = 0
        }
    }


    private fun buildFilteredQuery(
        knnQuery: Query,
        filter: List<String>
    ): Query {
        val builder = BooleanQuery.Builder()
        builder.add(knnQuery, BooleanClause.Occur.MUST)

        val tagFilter = BooleanQuery.Builder()
        filter.forEach { v ->
            tagFilter.add(TermQuery(Term(TAG, v)), BooleanClause.Occur.SHOULD)
        }
        builder.add(tagFilter.build(), BooleanClause.Occur.FILTER)

        return builder.build()
    }

    private fun validate(vector: FloatArray) {
        require(vector.size == dimension) {
            "Vector dimension must be $dimension but was ${vector.size}"
        }
    }

    private fun document(id: String, content: String, tag: String, vector: FloatArray, searchText: String): Document =
        Document().apply {
            add(StringField(ID_FIELD, id, Field.Store.YES))
            add(StoredField(CONTENT_FIELD, content))
            add(TextField(SEARCH_TEXT_FIELD, searchText, Field.Store.NO))
            add(StringField(TAG, tag, Field.Store.YES))
            add(KnnFloatVectorField(VECTOR_FIELD, vector))
        }
}
