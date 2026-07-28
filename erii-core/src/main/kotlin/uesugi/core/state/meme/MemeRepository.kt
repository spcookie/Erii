package uesugi.core.state.meme

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import uesugi.common.data.HistoryTable
import uesugi.common.data.MessageType
import uesugi.common.data.ResourceTable
import uesugi.common.toolkit.ConfigHolder
import uesugi.common.toolkit.logger
import uesugi.core.manage.ManageListQuery
import uesugi.core.state.meme.MemeData.MemeEntity
import uesugi.core.state.meme.MemeData.MemeRecord
import uesugi.core.state.meme.MemeData.MemeScanStateEntity
import uesugi.core.state.meme.MemeData.MemeScanStateRecord
import uesugi.core.state.meme.MemeData.MemeScanStateTable
import uesugi.core.state.meme.MemeData.MemeTable
import uesugi.core.state.meme.MemeData.toRecord
import kotlin.time.Clock.System
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

/**
 * 表情包仓库 - 负责数据库操作
 *
 * 提供表情包的 CRUD 操作，包括：
 * - 查找、创建、更新表情包记录
 * - 获取待分析的表情包
 * - 扫描状态管理
 * - 使用统计
 */
class MemeRepository {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val log = logger()
    }

    /**
     * 根据MD5查找已存在的表情包
     *
     * @param botId 机器人标识
     * @param groupId 群组ID
     * @param md5 图片MD5值
     * @return 表情包记录，如果不存在则返回 null
     */
    fun findByMd5(botId: String, groupId: String, md5: String): MemeRecord? {
        return transaction {
            MemeEntity.find {
                (MemeTable.botMark eq botId) and
                        (MemeTable.groupId eq groupId) and
                        (MemeTable.md5 eq md5)
            }.firstOrNull()?.toRecord()
        }
    }

    /**
     * 添加或更新表情包
     *
     * - 如果不存在：创建新记录
     * - 如果存在：追加上下文，增加计数（上下文最大400条）
     *
     * @param botId 机器人标识
     * @param groupId 群组ID
     * @param resourceId 资源ID
     * @param md5 图片MD5值
     * @param context 上下文消息
     * @return 更新后的表情包记录
     */
    @OptIn(ExperimentalTime::class)
    fun addOrUpdateMeme(
        botId: String,
        groupId: String,
        resourceId: Int,
        md5: String,
        context: String?
    ): MemeRecord {
        return transaction {
            val existing = MemeEntity.find {
                (MemeTable.botMark eq botId) and
                        (MemeTable.groupId eq groupId) and
                        (MemeTable.md5 eq md5)
            }.firstOrNull()

            if (existing != null) {
                // 已存在：追加上下文，增加计数
                val existingContexts = try {
                    json.decodeFromString<List<String>>(existing.contexts).toMutableList()
                } catch (_: Exception) {
                    mutableListOf()
                }

                if (!context.isNullOrBlank() && context !in existingContexts) {
                    existingContexts.add(context)
                    // 限制最大400条，保留最新的
                    if (existingContexts.size > ConfigHolder.getStateTuning().meme.maxContexts) {
                        existingContexts.removeAt(0)
                    }
                }

                existing.contexts = json.encodeToString(existingContexts)
                existing.seenCount = existing.seenCount + 1
                existing.updatedAt = System.now().toLocalDateTime(TimeZone.currentSystemDefault())

                log.debug("更新表情包: md5=$md5, seenCount=${existing.seenCount}, contexts=${existingContexts.size}")
                existing.toRecord()
            } else {
                // 新建记录
                val newContexts = if (!context.isNullOrBlank()) {
                    listOf(context)
                } else {
                    emptyList()
                }

                MemeEntity.new {
                    this.botMark = botId
                    this.groupId = groupId
                    this.resourceId = resourceId
                    this.md5 = md5
                    this.contexts = json.encodeToString(newContexts)
                    this.seenCount = 1
                    this.lastAnalyzedCount = 0
                    this.usageCount = 0
                    this.createdAt = System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                    this.updatedAt = System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                }.toRecord()
            }
        }
    }

    /**
     * 更新分析结果
     *
     * @param memeId 表情包ID
     * @param description 描述
     * @param purpose 用途
     * @param tags 标签
     * @param vectorId 向量ID
     * @param analyzedCount 分析时的累计计数
     */
    @OptIn(ExperimentalTime::class)
    fun updateAnalysis(
        memeId: Int,
        description: String,
        purpose: String,
        tags: String,
        vectorId: String,
        analyzedCount: Int
    ) {
        transaction {
            val memo = MemeEntity.findById(memeId)
            memo?.let {
                it.lastAnalyzedCount = analyzedCount
                it.description = description
                it.purpose = purpose
                it.tags = tags
                it.vectorId = vectorId
                it.updatedAt = System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                log.debug("更新分析结果: memeId=$memeId, analyzedCount=$analyzedCount, description=$description")
            }
        }
    }

    /**
     * 更新使用次数
     *
     * @param memeId 表情包ID
     */
    @OptIn(ExperimentalTime::class)
    fun incrementUsageCount(memeId: Int) {
        transaction {
            val memo = MemeEntity.findById(memeId)
            memo?.let {
                it.usageCount += 1
                it.lastUsedAt = System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            }
        }
    }

    /**
     * 根据ID获取表情包
     *
     * @param id 表情包ID
     * @return 表情包记录
     */
    fun getMemoById(id: Int): MemeRecord? {
        return transaction {
            MemeEntity.findById(id)?.toRecord()
        }
    }

    /**
     * 获取所有表情包
     *
     * @param botId 机器人标识
     * @param groupId 群组ID
     * @return 表情包记录列表
     */
    fun getAllMemos(
        botId: String,
        groupId: String,
        offset: Int = 0,
        limit: Int = 0
    ): Pair<List<MemeRecord>, Int> = getAllMemos(
        botId,
        groupId,
        ManageListQuery(offset = offset, limit = limit)
    )

    fun getAllMemos(botId: String, groupId: String, listQuery: ManageListQuery): Pair<List<MemeRecord>, Int> {
        return transaction {
            var condition: Op<Boolean> =
                (MemeTable.botMark eq botId) and (MemeTable.groupId eq groupId)
            if (listQuery.search.isNotBlank()) {
                condition = condition and (
                        (MemeTable.md5.lowerCase() like listQuery.searchPattern) or
                                (MemeTable.description.lowerCase() like listQuery.searchPattern) or
                                (MemeTable.purpose.lowerCase() like listQuery.searchPattern) or
                                (MemeTable.tags.lowerCase() like listQuery.searchPattern)
                        )
            }
            val total = MemeEntity.find { condition }.count().toInt()
            val query = MemeTable
                .selectAll()
                .where { condition }
            when (listQuery.sortBy) {
                "id" -> query.orderBy(MemeTable.id to listQuery.sortOrder)
                "description" -> query.orderBy(MemeTable.description to listQuery.sortOrder, MemeTable.id to listQuery.sortOrder)
                "seenCount" -> query.orderBy(MemeTable.seenCount to listQuery.sortOrder, MemeTable.id to listQuery.sortOrder)
                "usageCount" -> query.orderBy(MemeTable.usageCount to listQuery.sortOrder, MemeTable.id to listQuery.sortOrder)
                else -> query.orderBy(MemeTable.createdAt to SortOrder.DESC, MemeTable.id to SortOrder.DESC)
            }
            val pageQuery = if (listQuery.limit > 0) {
                query.limit(listQuery.limit).offset(listQuery.offset.toLong())
            } else {
                query.offset(listQuery.offset.toLong())
            }
            val items = MemeEntity.wrapRows(pageQuery).map { it.toRecord() }
            items to total
        }
    }

    /**
     * 获取待分析的表情包
     *
     * 条件：seenCount >= 3 且 seenCount 是 3 的倍数 且 seenCount > lastAnalyzedCount
     *
     * @param botId 机器人标识
     * @param groupId 群组ID
     * @return 待分析的表情包列表
     */
    fun getPendingAnalysisMemes(botId: String, groupId: String): List<MemeRecord> {
        return transaction {
            val analyzeThreshold = ConfigHolder.getStateTuning().meme.analyzeThreshold
            MemeEntity.find {
                (MemeTable.botMark eq botId) and
                        (MemeTable.groupId eq groupId) and
                        (MemeTable.seenCount greaterEq analyzeThreshold)
            }.map { it.toRecord() }
                .filter { memo ->
                    // seenCount 是 3 的倍数 且 大于上次分析的计数
                    val neverAnalyzed = memo.lastAnalyzedCount <= 0
                    val reachedInitialThreshold = memo.seenCount >= analyzeThreshold
                    val doubledSinceLastAnalysis = memo.seenCount >= memo.lastAnalyzedCount * 2
                    reachedInitialThreshold && (neverAnalyzed || doubledSinceLastAnalysis)
                }
        }
    }

    /**
     * 获取所有已分析的表情包（用于搜索）
     *
     * @param botId 机器人标识
     * @param groupId 群组ID，如果为空则返回所有群组的表情包
     * @return 已分析的表情包列表
     */
    fun getAnalyzedMemes(botId: String, groupId: String): List<MemeRecord> {
        return transaction {
            val analyzeThreshold = ConfigHolder.getStateTuning().meme.analyzeThreshold
            val query = if (groupId.isBlank()) {
                // 获取所有群组的已分析表情包
                MemeEntity.find {
                    (MemeTable.botMark eq botId) and
                            (MemeTable.lastAnalyzedCount greaterEq analyzeThreshold)
                }
            } else {
                // 获取指定群组的已分析表情包
                MemeEntity.find {
                    (MemeTable.botMark eq botId) and
                            (MemeTable.groupId eq groupId) and
                            (MemeTable.lastAnalyzedCount greaterEq analyzeThreshold)
                }
            }
            query.map { it.toRecord() }
        }
    }

    fun getAnalyzedMemesForRebuild(botId: String, groupId: String): List<MemeRecord> = transaction {
        MemeEntity.find {
            (MemeTable.botMark eq botId) and
                    (MemeTable.groupId eq groupId) and
                    (MemeTable.lastAnalyzedCount greater 0)
        }
            .map { it.toRecord() }
    }

    fun getAllMemeGroups(): List<Pair<String, String>> = transaction {
        MemeTable
            .select(MemeTable.botMark, MemeTable.groupId)
            .withDistinct(true)
            .map { it[MemeTable.botMark] to it[MemeTable.groupId] }
            .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
    }

    @OptIn(ExperimentalTime::class)
    fun updateMeme(id: Int, description: String?, purpose: String?, tags: String?): MemeRecord? {
        return transaction {
            val memo = MemeEntity.findById(id)
            memo?.let {
                description?.let { d -> it.description = d }
                purpose?.let { p -> it.purpose = p }
                tags?.let { t -> it.tags = t }
                it.updatedAt = System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                log.debug("更新表情包元数据: memeId=$id")
                it.toRecord()
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    fun createMeme(
        botId: String,
        groupId: String,
        resourceId: Int,
        md5: String,
        description: String?,
        purpose: String?,
        tags: String?
    ): MemeRecord {
        return transaction {
            MemeEntity.new {
                this.botMark = botId
                this.groupId = groupId
                this.resourceId = resourceId
                this.md5 = md5
                this.contexts = json.encodeToString(emptyList<String>())
                this.seenCount = 1
                this.lastAnalyzedCount = 0
                this.usageCount = 0
                this.description = description
                this.purpose = purpose
                this.tags = tags
                this.createdAt = System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                this.updatedAt = System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            }.toRecord()
        }
    }

    fun updateVectorId(id: Int, vectorId: String) = transaction {
        MemeTable.update({ MemeTable.id eq id }) { it[MemeTable.vectorId] = vectorId }
    }

    fun deleteMemo(id: Int): Boolean = transaction {
        val memo = MemeEntity.findById(id)
        memo?.delete()
        memo != null
    }

    /**
     * 删除低热度表情包
     *
     * 低热度判定（同时满足）：
     * - updatedAt < daysAgo 天前（一段时间内没有新的出现）
     * - seenCount < configured low-heat threshold（从未达到分析阈值，仍为噪声）
     *
     * @param daysAgo 距今天数（默认 7 天）
     * @return 已删除的表情包记录列表（用于后续清理向量存储等关联资源）
     */
    fun findMemesByResourceId(resourceId: Int): List<MemeRecord> {
        return transaction {
            MemeEntity.find {
                MemeTable.resourceId eq resourceId
            }.map { it.toRecord() }
        }
    }

    @OptIn(ExperimentalTime::class)
    fun deleteLowHeatMemes(daysAgo: Int = 7): List<MemeRecord> {
        return transaction {
            val cutoff = System.now()
                .minus(daysAgo.days)
                .toLocalDateTime(TimeZone.currentSystemDefault())
            val lowHeatSeenThreshold = ConfigHolder.getStateTuning().meme.lowHeatSeenThreshold

            val targets = MemeEntity.find {
                (MemeTable.updatedAt less cutoff) and
                        (MemeTable.seenCount less lowHeatSeenThreshold)
            }.toList()

            val records = targets.map { it.toRecord() }
            targets.forEach { it.delete() }

            if (records.isNotEmpty()) {
                log.info(
                    "Clean up low-popularity emojis: {} ({} not updated in days and seenCount < {})",
                    records.size, daysAgo, lowHeatSeenThreshold
                )
            }
            records
        }
    }

    fun latestHistoryId(botMark: String, groupId: String): Int? = transaction {
        HistoryTable
            .select(HistoryTable.id)
            .where { (HistoryTable.botMark eq botMark) and (HistoryTable.groupId eq groupId) }
            .orderBy(HistoryTable.id to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(HistoryTable.id)
            ?.value
    }

    /**
     * 获取扫描状态
     *
     * @param botId 机器人标识
     * @param groupId 群组ID
     * @return 扫描状态记录
     */
    fun getScanState(botId: String, groupId: String): MemeScanStateRecord? {
        return transaction {
            MemeScanStateEntity.find {
                (MemeScanStateTable.botMark eq botId) and (MemeScanStateTable.groupId eq groupId)
            }.firstOrNull()?.toRecord()
        }
    }

    /**
     * 更新扫描状态
     *
     * @param botId 机器人标识
     * @param groupId 群组ID
     * @param lastHistoryId 最后扫描的 history id
     */
    @OptIn(ExperimentalTime::class)
    fun updateScanState(botId: String, groupId: String, lastHistoryId: Int) {
        transaction {
            val existing = MemeScanStateEntity.find {
                (MemeScanStateTable.botMark eq botId) and (MemeScanStateTable.groupId eq groupId)
            }.firstOrNull()

            if (existing != null) {
                existing.lastHistoryId = lastHistoryId
                existing.lastScanAt = System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            } else {
                MemeScanStateEntity.new {
                    this.botMark = botId
                    this.groupId = groupId
                    this.lastHistoryId = lastHistoryId
                    this.lastScanAt = System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                }
            }
            log.debug("更新扫描状态: botId=$botId, groupId=$groupId, lastHistoryId=$lastHistoryId")
        }
    }

    /**
     * 获取群组中最近的图片消息及其MD5
     *
     * @param botId 机器人标识
     * @param groupId 群组ID
     * @param lastHistoryId 只获取 id 大于此值的消息（用于增量扫描）
     * @param limit 最多返回条数
     * @return 图片消息列表
     */
    fun getRecentImageMessages(
        botId: String,
        groupId: String,
        lastHistoryId: Int = 0,
        limit: Int = 500
    ): List<ImageMessageWithMd5> {
        return transaction {
            // 构建基础条件
            val baseCondition = (HistoryTable.botMark eq botId) and
                    (HistoryTable.groupId eq groupId) and
                    (HistoryTable.messageType eq MessageType.IMAGE)

            // 如果有 lastHistoryId，则添加条件
            val condition = if (lastHistoryId > 0) {
                baseCondition and (HistoryTable.id greater lastHistoryId)
            } else {
                baseCondition
            }

            HistoryTable
                .join(
                    ResourceTable,
                    JoinType.INNER,
                    additionalConstraint = { ResourceTable.id eq HistoryTable.resourceId }
                )
                .select(
                    HistoryTable.id,
                    HistoryTable.content,
                    HistoryTable.createdAt,
                    ResourceTable.id,
                    ResourceTable.md5
                )
                .where { condition }
                .orderBy(HistoryTable.id, SortOrder.ASC)
                .limit(limit)
                .map { row ->
                    ImageMessageWithMd5(
                        historyId = row[HistoryTable.id].value,
                        content = row[HistoryTable.content],
                        resourceId = row[ResourceTable.id].value,
                        md5 = row[ResourceTable.md5],
                        createdAt = row[HistoryTable.createdAt]
                    )
                }
        }
    }
}
