package uesugi.core.state.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.case
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.not
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uesugi.common.data.HistoryRecord
import uesugi.common.toolkit.ConfigHolder
import uesugi.common.toolkit.logger
import uesugi.core.message.history.truncateContent
import uesugi.core.manage.ManageListQuery
import uesugi.core.state.dispatch.StateWorkResult
import uesugi.core.state.summary.SummaryEntity
import uesugi.core.state.summary.SummaryTable
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

/**
 * 记忆服务 - 负责记忆处理的业务逻辑
 */
class MemoryService(
    private val memoryAgent: MemoryAgent,
    private val memoryRepository: MemoryRepository,
    private val factVectorStoreFactory: FactVectorStoreFactory,
    private val factGraphStoreFactory: FactGraphStoreFactory
) {

    companion object {
        private val log = logger()
    }

    /**
     * 处理单个群组的记忆
     */
    suspend fun processGroupMemory(
        botMark: String,
        groupId: String,
        batchLimit: Int = ConfigHolder.getStateTuning().memory.batchLimit,
        minimumMessages: Int = ConfigHolder.getStateTuning().memory.minMessages,
        force: Boolean = false
    ): StateWorkResult {
        log.debug("开始处理群组记忆, groupId=$groupId")

        try {
            // 1. 获取需要处理的历史消息
            val memoryState = withContext(Dispatchers.IO) {
                memoryRepository.getMemoryState(botMark, groupId)
            }
            val initialBatch = memoryState == null
            val lastId = memoryState?.lastProcessedHistoryId ?: 0

            val histories = withContext(Dispatchers.IO) {
                if (initialBatch) {
                    memoryRepository.getLatestHistories(botMark, groupId, batchLimit)
                } else {
                    memoryRepository.getHistoriesToProcess(botMark, groupId, lastId, batchLimit)
                }
            }

            if (histories.isEmpty()) {
                log.debug("群组 $groupId 没有新消息需要处理")
                return StateWorkResult(0, lastId, hasMore = false)
            }

            if (!force && histories.size < minimumMessages) {
                log.debug("群组 $groupId 消息数量不足 $minimumMessages 条，等待更多消息")
                return StateWorkResult(0, lastId, hasMore = false)
            }

            log.debug("群组 $groupId 获取到 ${histories.size} 条新消息")

            // 2. 过滤空内容消息
            val maxMessageLength = ConfigHolder.getAgentMaxMessageLength()
            val messages = histories
                .filter { !it.content.isNullOrBlank() }
                .map { it.truncateContent(maxMessageLength) }

            if (messages.isEmpty()) {
                log.debug("群组 $groupId 过滤后无有效消息,跳过处理")
                val maxHistoryId = histories.maxOf { it.id!! }
                withContext(Dispatchers.IO) {
                    memoryRepository.updateMemoryState(botMark, groupId, maxHistoryId)
                }
                return StateWorkResult(
                    histories.size,
                    maxHistoryId,
                    hasMore = !initialBatch && histories.size == batchLimit
                )
            }

            // 3. 按用户分组
            val messagesByUser = messages.groupBy { it.userId }

            // 4. 并发处理
            val processedSuccessfully = coroutineScope {
                // 4.1 用户画像和偏好 (按用户)
                val profileJob = async {
                    messagesByUser
                        .filterKeys { it != botMark }
                        .all { (userId, userMessages) ->
                            processUserProfile(botMark, groupId, userId, userMessages)
                        }
                }

                // 4.2 事实记忆提取
                val factsJob = async {
                    organizeFacts(botMark, groupId, messages)
                }

                profileJob.await() && factsJob.await()
            }

            if (!processedSuccessfully) {
                log.warn("Memory processing partially failed, keeping cursor for retry, groupId=$groupId")
                error("Memory processing partially failed for group $groupId")
            }

            // 5. 更新记忆处理状态
            val maxHistoryId = histories.maxOf { it.id!! }
            withContext(Dispatchers.IO) {
                memoryRepository.updateMemoryState(botMark, groupId, maxHistoryId)
            }

            log.debug("群组 $groupId 记忆处理完成, 最大 historyId=$maxHistoryId")
            return StateWorkResult(
                histories.size,
                maxHistoryId,
                hasMore = !initialBatch && histories.size == batchLimit
            )

        } catch (e: Exception) {
            log.error("处理群组 $groupId 记忆失败", e)
            throw e
        }
    }

    /**
     * 整理事实记忆
     *
     * 流程：
     * 1. LLM 提取事实（extractFacts）
     * 2. LLM 冲突解决（resolveConflicts）→ ADD / DELETE / NONE
     * 3. 批量执行决策
     * 4. 统一向量同步
     */
    private suspend fun organizeFacts(
        botMark: String,
        groupId: String,
        messages: List<HistoryRecord>
    ): Boolean {
        try {
            log.debug("开始整理事实记忆, groupId=$groupId, message count=${messages.size}")
            memoryAgent.organize(botMark, groupId, messages)

            log.info("Fact memory sorting completed, botId=$botMark, groupId=$groupId")
            return true
        } catch (e: Exception) {
            log.error("Failed to organize fact memory, groupId=$groupId", e)
            return false
        }
    }

    /**
     * 处理用户画像
     */
    private suspend fun processUserProfile(
        botMark: String,
        groupId: String,
        userId: String,
        messages: List<HistoryRecord>
    ): Boolean {
        try {
            log.debug("开始处理用户画像, groupId=$groupId, userId=$userId")

            val existing = withContext(Dispatchers.IO) {
                memoryRepository.findOrCreateUserProfile(botMark, groupId, userId)
            }

            val analysis = memoryAgent.analyzeUserProfile(messages, existing)

            // 保存到数据库
            withContext(Dispatchers.IO) {
                memoryRepository.updateUserProfile(botMark, groupId, userId, analysis.profile, analysis.preferences)
                log.info("User portrait has been updated, botId=$botMark, groupId=$groupId, userId=$userId")
            }
            return true

        } catch (e: Exception) {
            log.error("Failed to process user portrait, groupId=$groupId, userId=$userId", e)
            return false
        }
    }

    suspend fun recallFactsForAgent(
        botMark: String,
        groupId: String,
        subjects: List<String>,
        query: String,
        candidateLimit: Int = 3,
        minScore: Float = 0.7f,
        graphLimit: Int = 2
    ): List<FactsEntity> {
        if (query.isBlank() || candidateLimit <= 0) return emptyList()

        val bm25Results = try {
            factVectorStoreFactory.searchByKeyword(query, groupId, botMark, candidateLimit)
        } catch (e: Exception) {
            log.warn("BM25 search failed for agent fact recall", e)
            emptyList()
        }

        val vectorResults = try {
            factVectorStoreFactory.search(query, groupId, botMark, candidateLimit)
        } catch (e: Exception) {
            log.warn("Vector search failed for agent fact recall", e)
            emptyList()
        }

        val seedResults = mergeFactSearchResults(
            bm25Results = bm25Results,
            vectorResults = vectorResults,
            limit = candidateLimit
        ).filter { it.score >= minScore }

        val seedFactsById = getVisibleFactEntitiesByIds(
            botMark = botMark,
            groupId = groupId,
            subjects = subjects,
            factIds = seedResults.mapNotNull { it.factId }
        )
        val seedFacts = seedResults.mapNotNull { result ->
            result.factId?.let { seedFactsById[it] }
        }

        val expandedFacts = if (graphLimit > 0) {
            expandByGraph(botMark, groupId, subjects, seedFacts, graphLimit)
        } else {
            emptyList()
        }

        val recalledFacts = (seedFacts + expandedFacts)
            .distinctBy { it.id.value }
        markFactsRecalled(recalledFacts)
        return recalledFacts
    }

    suspend fun searchFactsVector(
        botMark: String,
        groupId: String,
        query: String,
        limit: Int = 10
    ): MemoryVectorSearchResponse {
        if (query.isBlank() || limit <= 0) {
            return MemoryVectorSearchResponse(query = query, results = emptyList())
        }
        val effectiveLimit = limit.coerceAtMost(100)

        val bm25Results = try {
            factVectorStoreFactory.searchByKeyword(query, groupId, botMark, effectiveLimit)
        } catch (e: Exception) {
            log.warn("Management BM25 search failed for facts", e)
            emptyList()
        }

        val vectorResults = try {
            factVectorStoreFactory.search(query, groupId, botMark, effectiveLimit)
        } catch (e: Exception) {
            log.warn("Management vector search failed for facts", e)
            emptyList()
        }

        val mergedResults = mergeFactSearchResults(
            bm25Results = bm25Results,
            vectorResults = vectorResults,
            limit = effectiveLimit
        )
        val factsById = getValidFactRecordsByIds(botMark, groupId, mergedResults.mapNotNull { it.factId })
        val results = mergedResults.mapNotNull { result ->
            val fact = result.factId?.let { factsById[it] } ?: return@mapNotNull null
            MemoryFactSearchResult(
                fact = fact,
                score = result.score,
                vectorId = result.vectorId,
                source = result.source
            )
        }

        return MemoryVectorSearchResponse(query = query, results = results)
    }

    private fun mergeFactSearchResults(
        bm25Results: List<FactSearchResult>,
        vectorResults: List<FactSearchResult>,
        limit: Int
    ): List<ScoredFactSearchResult> {
        val byFactId = linkedMapOf<Int, ScoredFactSearchResult>()
        fun add(result: FactSearchResult, source: String) {
            val factId = result.factId ?: return
            val scored = ScoredFactSearchResult(result, source)
            val existing = byFactId[factId]
            if (existing == null || scored.score > existing.score) {
                byFactId[factId] = scored
            }
        }
        bm25Results.forEach { add(it, "bm25") }
        vectorResults.forEach { add(it, "vector") }
        return byFactId.values
            .sortedByDescending { it.score }
            .take(limit)
    }

    private data class ScoredFactSearchResult(
        val result: FactSearchResult,
        val source: String
    ) {
        val factId: Int? = result.factId
        val score: Float = result.score
        val vectorId: String = result.vectorId
    }

    suspend fun rebuildFactEntities(): FactEntityRebuildReport {
        val report = FactEntityRebuildRunner(memoryRepository) { fact ->
            extractEntityCandidates(fact.description)
        }.run()
        log.info("Fact entities rebuilt: ${report.summary}")
        report.items.filter { it.error != null }.forEach { item ->
            log.warn("Fact entity rebuild failed, factId=${item.factId}, error=${item.error}")
        }
        return report
    }

    suspend fun rebuildFactVectors(): MemoryRebuildResult {
        val groups = withContext(Dispatchers.IO) {
            memoryRepository.getAllFactGroups()
        }

        var factCount = 0
        groups.forEach { (botMark, groupId) ->
            val facts = withContext(Dispatchers.IO) {
                memoryRepository.getValidFacts(botMark, groupId)
            }
            factCount += facts.size
            val indexed = factVectorStoreFactory.rebuildStore(
                botMark = botMark,
                groupId = groupId,
                facts = facts
            )
            withContext(Dispatchers.IO) {
                memoryRepository.updateFactVectorIds(indexed)
            }
        }
        val removedStores = factVectorStoreFactory.removeOrphanStores(groups)
        if (removedStores.isNotEmpty()) {
            log.info("Removed orphan fact vector stores: ${removedStores.joinToString()}")
        }

        log.info("Fact vector stores rebuilt, groups=${groups.size}, facts=$factCount")
        return MemoryRebuildResult(
            facts = factCount,
            groups = groups.map { (botMark, groupId) -> "$botMark:$groupId" }
        )
    }

    fun rebuildFactGraphs(): MemoryRebuildResult {
        val groups = memoryRepository.getAllFactGroups()

        groups.forEach { (botMark, groupId) ->
            factGraphStoreFactory.rebuildStore(botMark, groupId)
        }
        val removedStores = factGraphStoreFactory.removeOrphanStores(groups)
        if (removedStores.isNotEmpty()) {
            log.info("Removed orphan fact graph stores: ${removedStores.joinToString()}")
        }
        val factCount = memoryRepository.countAllValidFacts()

        log.info("Fact graph stores rebuilt, groups=${groups.size}, facts=$factCount")
        return MemoryRebuildResult(
            facts = factCount,
            groups = groups.map { (botMark, groupId) -> "$botMark:$groupId" }
        )
    }

    suspend fun searchFactsGraph(
        botMark: String,
        groupId: String,
        query: String,
        limit: Int = 10
    ): MemoryGraphSearchResponse {
        if (query.isBlank() || limit <= 0) {
            return MemoryGraphSearchResponse(
                query = query,
                seedResults = emptyList(),
                expandedResults = emptyList(),
                nodes = emptyList(),
                edges = emptyList()
            )
        }
        val effectiveLimit = limit.coerceAtMost(100)
        val vectorResponse = searchFactsVector(botMark, groupId, query, effectiveLimit)
        val seedResults = vectorResponse.results.map { it.copy(source = "seed") }
        val seedIds = seedResults.map { it.fact.id }

        val entityNames = factGraphStoreFactory.expandByFacts(seedIds, botMark, groupId).distinct()
        val expandedIds = if (entityNames.isEmpty()) {
            emptyList()
        } else {
            factGraphStoreFactory.expandByEntities(entityNames, botMark, groupId)
                .distinct()
                .filter { it !in seedIds }
                .take(effectiveLimit)
        }

        val expandedFactsById = getValidFactRecordsByIds(botMark, groupId, expandedIds)
        val expandedResults = expandedIds.mapNotNull { factId ->
            expandedFactsById[factId]?.let { fact ->
                MemoryFactSearchResult(
                    fact = fact,
                    score = null,
                    vectorId = fact.vectorId,
                    source = "expanded"
                )
            }
        }

        val allResults = seedResults + expandedResults
        val nodes = buildMemoryGraphNodes(allResults)
        val edges = buildMemoryGraphEdges(allResults)

        return MemoryGraphSearchResponse(
            query = query,
            seedResults = seedResults,
            expandedResults = expandedResults,
            nodes = nodes,
            edges = edges
        )
    }

    /**
     * 图扩展：从已召回事实出发，双向一跳查询找到更多相关事实。
     * 正向：seed facts → entities（SPARQL 反向查询）→ more facts（SPARQL 正向查询）
     */
    private fun expandByGraph(
        botMark: String,
        groupId: String,
        subjects: List<String>,
        seedFacts: List<FactsEntity>,
        limit: Int = Int.MAX_VALUE
    ): List<FactsEntity> {
        val seedIds = seedFacts.map { it.id.value }
        if (seedIds.isEmpty()) return emptyList()

        // 反向：seed fact IDs → entities
        val entityNames = factGraphStoreFactory.expandByFacts(seedIds, botMark, groupId)
        if (entityNames.isEmpty()) return emptyList()

        // 正向：entities → more fact IDs
        val expandedFactIds = factGraphStoreFactory.expandByEntities(entityNames, botMark, groupId)
        val seedIdSet = seedIds.toSet()
        val novelIds = expandedFactIds.filter { it !in seedIdSet }

        if (novelIds.isEmpty() || limit <= 0) return emptyList()
        val order = novelIds.withIndex().associate { (index, id) -> id to index }

        return transaction {
            FactsEntity.find {
                (FactsTable.id inList novelIds) and FactsTable.validCondition(botMark, groupId)
            }
                .filter { fact -> fact.isVisibleTo(subjects) }
                .sortedBy { fact -> order[fact.id.value] ?: Int.MAX_VALUE }
                .take(limit)
        }
    }

    private fun getVisibleFactEntitiesByIds(
        botMark: String,
        groupId: String,
        subjects: List<String>,
        factIds: List<Int>
    ): Map<Int, FactsEntity> {
        val ids = factIds.distinct()
        if (ids.isEmpty()) return emptyMap()

        return transaction {
            FactsEntity.find {
                (FactsTable.id inList ids) and FactsTable.validCondition(botMark, groupId)
            }
                .filter { fact -> fact.isVisibleTo(subjects) }
                .associateBy { it.id.value }
        }
    }

    private fun getValidFactRecordsByIds(
        botMark: String,
        groupId: String,
        factIds: List<Int>
    ): Map<Int, FactsRecord> {
        val ids = factIds.distinct()
        if (ids.isEmpty()) return emptyMap()

        return transaction {
            FactsEntity.find {
                (FactsTable.id inList ids) and FactsTable.validCondition(botMark, groupId)
            }.associate { it.id.value to it.toRecord() }
        }
    }

    private fun buildMemoryGraphNodes(results: List<MemoryFactSearchResult>): List<MemoryGraphNode> {
        val factNodes = results.map { result ->
            MemoryGraphNode(
                id = factNodeId(result.fact.id),
                type = "fact",
                label = "#${result.fact.id} ${result.fact.keyword}",
                source = result.source
            )
        }
        val entityNodes = results
            .flatMap { it.fact.entities }
            .distinct()
            .map { entity ->
                MemoryGraphNode(
                    id = entityNodeId(entity),
                    type = "entity",
                    label = entity
                )
            }
        return factNodes + entityNodes
    }

    private fun buildMemoryGraphEdges(results: List<MemoryFactSearchResult>): List<MemoryGraphEdge> =
        results
            .flatMap { result ->
                result.fact.entities.map { entity ->
                    MemoryGraphEdge(
                        from = factNodeId(result.fact.id),
                        to = entityNodeId(entity),
                        label = "involves"
                    )
                }
            }
            .distinct()

    private fun factNodeId(factId: Int): String = "fact:$factId"

    private fun entityNodeId(entity: String): String = "entity:$entity"

    private suspend fun markFactsRecalled(facts: List<FactsEntity>) {
        val ids = facts.map { it.id.value }.distinct()
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) {
            memoryRepository.markFactsRecalled(ids)
        }
    }

    fun deleteExpiredFacts(): Int {
        val deletedFacts = memoryRepository.deleteExpiredFacts()
        deleteVectors(deletedFacts)
        deleteGraphs(deletedFacts)
        log.info("Expired fact memory cleanup completed, deleted=${deletedFacts.size}")
        return deletedFacts.size
    }

    @OptIn(ExperimentalTime::class)
    fun deleteStaleUnrecalledFacts(staleRecallDays: Long): Int {
        val cutoff = Clock.System.now()
            .minus(staleRecallDays.days)
            .toLocalDateTime(TimeZone.currentSystemDefault())
        val deletedFacts = memoryRepository.deleteStaleUnrecalledFacts(cutoff)
        deleteVectors(deletedFacts)
        deleteGraphs(deletedFacts)
        log.info("Stale unrecalled fact memory cleanup completed, days=$staleRecallDays, deleted=${deletedFacts.size}")
        return deletedFacts.size
    }

    private fun deleteVectors(facts: List<FactsRecord>) {
        facts.forEach { fact ->
            fact.vectorId?.let { vectorId ->
                factVectorStoreFactory.deleteVector(vectorId, fact.botMark, fact.groupId)
            }
        }
    }

    private fun deleteGraphs(facts: List<FactsRecord>) {
        facts.forEach { fact ->
            factGraphStoreFactory.removeFactEntities(fact.id, fact.botMark, fact.groupId)
        }
    }

    fun getAllFactsByGroup(
        botMark: String,
        groupId: String,
        offset: Int = 0,
        limit: Int = 0
    ): Pair<List<FactsEntity>, Int> {
        return transaction {
            val condition = FactsTable.validCondition(botMark, groupId)
            val baseQuery = FactsEntity.find { condition }
            val total = baseQuery.count().toInt()
            val query = FactsTable
                .selectAll()
                .where { condition }
                .orderBy(FactsTable.createdAt to SortOrder.DESC)
            val pageQuery = if (limit > 0) {
                query.limit(limit).offset(offset.toLong())
            } else {
                query.offset(offset.toLong())
            }
            val items = FactsEntity.wrapRows(pageQuery).reversed().toList()
            items to total
        }
    }

    /**
     * Returns every fact for the management UI, including expired and superseded facts.
     * Results are sorted newest first before offset/limit are applied.
     */
    fun getAllFactsByGroupForManagement(
        botMark: String,
        groupId: String,
        offset: Int = 0,
        limit: Int = 0
    ): Pair<List<FactsEntity>, Int> = getAllFactsByGroupForManagement(
        botMark,
        groupId,
        ManageListQuery(offset = offset, limit = limit)
    )

    fun getAllFactsByGroupForManagement(
        botMark: String,
        groupId: String,
        listQuery: ManageListQuery
    ): Pair<List<FactsEntity>, Int> {
        return transaction {
            var condition: Op<Boolean> = (FactsTable.botMark eq botMark) and (FactsTable.groupId eq groupId)
            if (listQuery.search.isNotBlank()) {
                condition = when (listQuery.search.lowercase()) {
                    "valid" -> condition and FactsTable.validCondition(botMark, groupId)
                    "invalid" -> condition and not(FactsTable.validCondition(botMark, groupId))
                    else -> {
                        val matchingScopes = Scopes.entries.filter {
                            it.name.lowercase().contains(listQuery.search.lowercase())
                        }
                        var searchCondition: Op<Boolean> =
                            (FactsTable.keyword.lowerCase() like listQuery.searchPattern) or
                                    (FactsTable.description.lowerCase() like listQuery.searchPattern) or
                                    (FactsTable.subjects.lowerCase() like listQuery.searchPattern)
                        if (matchingScopes.isNotEmpty()) {
                            searchCondition = searchCondition or (FactsTable.scopeType inList matchingScopes)
                        }
                        condition and searchCondition
                    }
                }
            }
            val total = FactsEntity.find { condition }.count().toInt()
            val query = FactsTable
                .selectAll()
                .where { condition }
            when (listQuery.sortBy) {
                "id" -> query.orderBy(FactsTable.id to listQuery.sortOrder)
                "valid" -> {
                    val validity = case()
                        .When(FactsTable.validCondition(botMark, groupId), intLiteral(1))
                        .Else(intLiteral(0))
                    query.orderBy(validity to listQuery.sortOrder, FactsTable.id to listQuery.sortOrder)
                }
                "keyword" -> query.orderBy(FactsTable.keyword to listQuery.sortOrder, FactsTable.id to listQuery.sortOrder)
                "scopeType" -> query.orderBy(FactsTable.scopeType to listQuery.sortOrder, FactsTable.id to listQuery.sortOrder)
                else -> query.orderBy(FactsTable.createdAt to SortOrder.DESC, FactsTable.id to SortOrder.DESC)
            }
            val pageQuery = if (listQuery.limit > 0) {
                query.limit(listQuery.limit).offset(listQuery.offset.toLong())
            } else {
                query.offset(listQuery.offset.toLong())
            }
            FactsEntity.wrapRows(pageQuery).toList() to total
        }
    }

    fun getFactSize(
        botMark: String,
        groupId: String
    ): Long {
        return transaction {
            FactsEntity.find { FactsTable.validCondition(botMark, groupId) }.count()
        }
    }

    fun getUserProfiles(
        botMark: String,
        groupId: String,
        userId: List<String>
    ): List<UserProfileEntity> {
        return transaction {
            UserProfileEntity.find {
                (UserProfileTable.botMark eq botMark) and
                        (UserProfileTable.groupId eq groupId) and
                        (UserProfileTable.userId inList userId)
            }.toList()
        }
    }

    fun getAllUserProfilesByGroup(
        botMark: String,
        groupId: String,
        offset: Int = 0,
        limit: Int = 0
    ): Pair<List<UserProfileEntity>, Int> = getAllUserProfilesByGroup(
        botMark,
        groupId,
        ManageListQuery(offset = offset, limit = limit, sortBy = "createdAt", ascending = true)
    )

    fun getAllUserProfilesByGroup(
        botMark: String,
        groupId: String,
        listQuery: ManageListQuery
    ): Pair<List<UserProfileEntity>, Int> {
        return transaction {
            var condition: Op<Boolean> =
                (UserProfileTable.botMark eq botMark) and
                        (UserProfileTable.groupId eq groupId)
            if (listQuery.search.isNotBlank()) {
                condition = condition and (
                        (UserProfileTable.userId.lowerCase() like listQuery.searchPattern) or
                                (UserProfileTable.profile.lowerCase() like listQuery.searchPattern) or
                                (UserProfileTable.preferences.lowerCase() like listQuery.searchPattern)
                        )
            }
            val total = UserProfileEntity.find { condition }.count().toInt()
            val query = UserProfileTable
                .selectAll()
                .where { condition }
            when (listQuery.sortBy) {
                "id" -> query.orderBy(UserProfileTable.id to listQuery.sortOrder)
                "userId" -> query.orderBy(UserProfileTable.userId to listQuery.sortOrder, UserProfileTable.id to listQuery.sortOrder)
                "createdAt" -> query.orderBy(UserProfileTable.createdAt to listQuery.sortOrder, UserProfileTable.id to listQuery.sortOrder)
                else -> query.orderBy(UserProfileTable.createdAt to SortOrder.DESC, UserProfileTable.id to SortOrder.DESC)
            }
            val pageQuery = if (listQuery.limit > 0) {
                query.limit(listQuery.limit).offset(listQuery.offset.toLong())
            } else {
                query.offset(listQuery.offset.toLong())
            }
            val items = UserProfileEntity.wrapRows(pageQuery).toList()
            items to total
        }
    }

    fun getUserProfileSize(
        botMark: String,
        groupId: String
    ): Long {
        return transaction {
            UserProfileEntity.find {
                (UserProfileTable.botMark eq botMark) and
                        (UserProfileTable.groupId eq groupId)
            }.count()
        }
    }

    fun getSummary(
        botMark: String,
        groupId: String
    ): SummaryEntity? {
        return transaction {
            SummaryEntity.find {
                (SummaryTable.botMark eq botMark) and
                        (SummaryTable.groupId eq groupId)
            }.orderBy(SummaryTable.createdAt to SortOrder.DESC)
                .firstOrNull()
        }
    }

    fun deleteFact(botId: String, groupId: String, id: Int): Boolean {
        val fact = memoryRepository.getFactById(id) ?: return false
        if (fact.botMark != botId || fact.groupId != groupId) return false
        fact.vectorId?.let { vectorId ->
            factVectorStoreFactory.deleteVector(vectorId, botId, groupId)
        }
        factGraphStoreFactory.removeFactEntities(id, botId, groupId)
        return memoryRepository.deleteFact(id)
    }

    suspend fun createFact(
        botMark: String,
        groupId: String,
        keyword: String,
        description: String,
        entities: List<String>,
        subjects: String,
        scopeType: Scopes
    ): FactsRecord? {
        val id = withContext(Dispatchers.IO) {
            memoryRepository.createFact(botMark, groupId, keyword, description, entities, subjects, scopeType)
        }
        val fact = withContext(Dispatchers.IO) {
            memoryRepository.getFactById(id)
        } ?: return null
        factGraphStoreFactory.addFactEntities(fact)
        return syncFactVector(fact)
    }

    suspend fun updateFact(
        botMark: String,
        groupId: String,
        id: Int,
        keyword: String,
        description: String,
        entities: List<String>,
        subjects: String,
        scopeType: Scopes
    ): FactsRecord? {
        val existing = memoryRepository.getFactById(id) ?: return null
        if (existing.botMark != botMark || existing.groupId != groupId) return null
        existing.vectorId?.let { vectorId ->
            factVectorStoreFactory.deleteVector(vectorId, botMark, groupId)
        }
        factGraphStoreFactory.removeFactEntities(id, botMark, groupId)
        val updated = withContext(Dispatchers.IO) {
            memoryRepository.updateFact(id, keyword, description, entities, subjects, scopeType)
        } ?: return null
        factGraphStoreFactory.addFactEntities(updated)
        return syncFactVector(updated)
    }

    private suspend fun syncFactVector(fact: FactsRecord): FactsRecord? {
        val vectorId = try {
            factVectorStoreFactory.indexFact(fact)
        } catch (e: Exception) {
            log.warn(
                "Failed to sync fact vector, factId=${fact.id}, botId=${fact.botMark}, groupId=${fact.groupId}",
                e
            )
            return fact
        }
        return withContext(Dispatchers.IO) {
            memoryRepository.updateFactVectorId(fact.id, vectorId)
            memoryRepository.getFactById(fact.id)
        }
    }
}
