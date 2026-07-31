package uesugi.core.component.storage

import org.eclipse.rdf4j.model.ValueFactory
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.nativerdf.NativeStore
import uesugi.common.toolkit.logger
import uesugi.core.state.memory.FactsRecord
import uesugi.core.state.memory.MemoryRepository
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * 实体图存储接口，维护 fact-entity 关联关系。
 *
 * 写侧：事实 ADD → 添加三元组；事实 DELETE → 移除三元组。
 * 读侧：给定实体列表，通过双向一跳查询找到关联的事实。
 */
interface GraphStore {
    fun rebuild()
    fun addFactEntities(fact: FactsRecord)
    fun removeFactEntities(factId: Int)
    fun expandByEntities(entityIds: List<String>): List<Int>
    fun expandByFacts(factIds: List<Int>): List<String>
    fun close() = Unit
}

/**
 * 基于 RDF4J NativeStore 的实体图存储实现，数据持久化到指定目录。
 */
class Rdf4jGraphStore(
    path: Path,
    private val botId: String,
    private val groupId: String,
    private val memoryRepository: MemoryRepository
) : GraphStore {

    companion object {
        private val log = logger()
        private const val NS = "http://erii/"
        private const val PROP_INVOLVES = "${NS}prop/involves"
    }

    private val vf: ValueFactory = SimpleValueFactory.getInstance()
    private val repo: SailRepository = SailRepository(NativeStore(path.toFile()))

    // ── Public API ──

    override fun rebuild() {
        log.info("Rebuilding graph store from database, botId=$botId, groupId=$groupId...")
        try {
            val allFacts = memoryRepository.getValidFacts(botId, groupId)
            repo.connection.use { conn ->
                conn.begin()
                conn.clear()
                for (fact in allFacts) {
                    val factIri = vf.createIRI(factUri(fact.id))
                    val involvesIri = vf.createIRI(PROP_INVOLVES)
                    for (entity in fact.entities) {
                        conn.add(factIri, involvesIri, vf.createIRI(entityUri(entity)))
                    }
                }
                conn.commit()
            }
            log.info("Graph store rebuilt: ${allFacts.size} facts loaded")
        } catch (e: Exception) {
            log.error("Failed to rebuild graph store", e)
            throw e
        }
    }

    override fun addFactEntities(fact: FactsRecord) {
        val entities = fact.entities
        if (entities.isEmpty()) return
        try {
            repo.connection.use { conn ->
                conn.begin()
                val factIri = vf.createIRI(factUri(fact.id))
                val involvesIri = vf.createIRI(PROP_INVOLVES)
                for (entity in entities) {
                    conn.add(factIri, involvesIri, vf.createIRI(entityUri(entity)))
                }
                conn.commit()
            }
        } catch (e: Exception) {
            log.error("Failed to add fact entities for factId=${fact.id}", e)
        }
    }

    override fun removeFactEntities(factId: Int) {
        try {
            repo.connection.use { conn ->
                conn.begin()
                conn.remove(vf.createIRI(factUri(factId)), null, null)
                conn.commit()
            }
        } catch (e: Exception) {
            log.error("Failed to remove fact entities for factId=$factId", e)
        }
    }

    override fun expandByEntities(entityIds: List<String>): List<Int> {
        if (entityIds.isEmpty()) return emptyList()
        try {
            val valuesClause = entityIds.joinToString(" ") { "<${entityUri(it)}>" }
            val queryStr = """
                PREFIX erii: <$NS>
                SELECT DISTINCT ?factId WHERE {
                  VALUES ?entity { $valuesClause }
                  ?fact <$PROP_INVOLVES> ?entity .
                  BIND(REPLACE(STR(?fact), "${NS}fact/", "") AS ?factId)
                }
            """.trimIndent()

            val results = mutableListOf<Int>()
            repo.connection.use { conn ->
                val tupleQuery = conn.prepareTupleQuery(queryStr)
                tupleQuery.evaluate().use { result ->
                    while (result.hasNext()) {
                        result.next()
                            .getValue("factId")
                            ?.stringValue()
                            ?.toIntOrNull()
                            ?.let { results.add(it) }
                    }
                }
            }
            return results
        } catch (e: Exception) {
            log.error("Graph expansion failed for entities=$entityIds", e)
            return emptyList()
        }
    }

    override fun expandByFacts(factIds: List<Int>): List<String> {
        if (factIds.isEmpty()) return emptyList()
        try {
            val valuesClause = factIds.joinToString(" ") { "<${factUri(it)}>" }
            val queryStr = """
                PREFIX erii: <$NS>
                SELECT DISTINCT ?entity WHERE {
                  VALUES ?fact { $valuesClause }
                  ?fact <$PROP_INVOLVES> ?entity .
                }
            """.trimIndent()

            val results = mutableListOf<String>()
            repo.connection.use { conn ->
                val tupleQuery = conn.prepareTupleQuery(queryStr)
                tupleQuery.evaluate().use { result ->
                    while (result.hasNext()) {
                        result.next()
                            .getValue("entity")
                            ?.stringValue()
                            ?.let { iri ->
                                val encoded = iri.substringAfter("${NS}entity/")
                                URLDecoder.decode(encoded, StandardCharsets.UTF_8)
                            }
                            ?.let { results.add(it) }
                    }
                }
            }
            return results
        } catch (e: Exception) {
            log.error("Reverse graph expansion failed for factIds=$factIds", e)
            return emptyList()
        }
    }

    override fun close() {
        repo.shutDown()
    }

    // ── Private helpers ──

    private fun factUri(factId: Int): String = "${NS}fact/$factId"

    private fun entityUri(entityName: String): String =
        "${NS}entity/${URLEncoder.encode(entityName, StandardCharsets.UTF_8)}"
}
