package uesugi.core.state.memory

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import uesugi.common.data.HistoryEntity
import uesugi.common.data.HistoryRecord
import uesugi.common.data.HistoryTable
import uesugi.common.data.toRecord
import uesugi.common.toolkit.logger
import kotlin.time.Clock

/**
 * 记忆仓库 - 负责数据库操作
 */
class MemoryRepository {

    companion object {
        private val log = logger()
    }

    /**
     * 查找需要处理记忆的群组
     * 规则: 自上次处理后有新消息的群组
     */
    fun findGroupsNeedProcessing(botId: String): List<String> {
        return transaction {
            // 查询所有群组的最新消息 ID
            val allGroupIds = HistoryTable
                .select(HistoryTable.groupId)
                .where { HistoryTable.botId eq botId }
                .groupBy(HistoryTable.groupId)
                .map { it[HistoryTable.groupId] }
                .distinct()

            // 过滤出有新消息的群组
            allGroupIds.filter { groupId ->
                val memoryState = MemoryStateEntity.find(
                    (MemoryStateTable.botId eq botId) and (MemoryStateTable.groupId eq groupId)
                ).firstOrNull()

                val lastProcessedId = memoryState?.lastProcessedHistoryId ?: 0

                // 检查是否有新消息
                val newMessageCount = HistoryEntity.count(
                    (HistoryTable.botId eq botId) and
                            (HistoryTable.groupId eq groupId) and
                            (HistoryTable.id greater lastProcessedId)
                )

                newMessageCount > 0
            }
        }
    }

    /**
     * 获取记忆处理状态
     */
    fun getMemoryState(botId: String, groupId: String): MemoryStateRecord? {
        return transaction {
            MemoryStateEntity.find(
                (MemoryStateTable.botId eq botId) and (MemoryStateTable.groupId eq groupId)
            ).firstOrNull()?.toRecord()
        }
    }

    /**
     * 更新记忆处理状态
     */
    fun updateMemoryState(botId: String, groupId: String, lastHistoryId: Int) {
        transaction {
            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

            val existing = MemoryStateEntity.find(
                (MemoryStateTable.botId eq botId) and (MemoryStateTable.groupId eq groupId)
            ).firstOrNull()

            if (existing != null) {
                existing.lastProcessedHistoryId = lastHistoryId
                existing.lastProcessedAt = now
            } else {
                MemoryStateEntity.new {
                    this.botId = botId
                    this.groupId = groupId
                    this.lastProcessedHistoryId = lastHistoryId
                    this.lastProcessedAt = now
                }
            }
            log.debug("记忆状态已更新, groupId=$groupId, lastHistoryId=$lastHistoryId")
        }
    }

    /**
     * 获取待处理的历史消息
     */
    fun getHistoriesToProcess(
        botId: String,
        groupId: String,
        lastHistoryId: Int,
        limit: Int = 200
    ): List<HistoryRecord> {
        return transaction {
            HistoryEntity.find(
                (HistoryTable.botId eq botId) and
                        (HistoryTable.groupId eq groupId) and
                        (HistoryTable.id greater lastHistoryId)
            )
                .orderBy(HistoryTable.id to SortOrder.ASC)
                .limit(limit)
                .map { it.toRecord() }
        }
    }

    /**
     * 获取最新一批历史消息，并按时间正序返回。
     *
     * 用于尚未建立处理游标的群组：只处理最新窗口，更早历史作为基线数据跳过。
     */
    fun getLatestHistories(botId: String, groupId: String, limit: Int): List<HistoryRecord> = transaction {
        HistoryEntity.find(
            (HistoryTable.botId eq botId) and (HistoryTable.groupId eq groupId)
        )
            .orderBy(HistoryTable.id to SortOrder.DESC)
            .limit(limit)
            .map { it.toRecord() }
            .asReversed()
    }

    /**
     * 查找或创建用户画像
     */
    fun findOrCreateUserProfile(botId: String, groupId: String, userId: String): UserProfileRecord {
        return transaction {
            val entity = UserProfileEntity.find(
                (UserProfileTable.botId eq botId) and
                        (UserProfileTable.groupId eq groupId) and
                        (UserProfileTable.userId eq userId)
            ).firstOrNull() ?: UserProfileEntity.new {
                this.botId = botId
                this.groupId = groupId
                this.userId = userId
                this.profile = ""
                this.preferences = ""
            }
            entity.toRecord()
        }
    }

    fun updateUserProfile(
        botId: String,
        groupId: String,
        userId: String,
        profile: String,
        preferences: String
    ): UserProfileRecord =
        transaction {
            val entity = UserProfileEntity.find(
                (UserProfileTable.botId eq botId) and
                        (UserProfileTable.groupId eq groupId) and
                        (UserProfileTable.userId eq userId)
            ).firstOrNull() ?: UserProfileEntity.new {
                this.botId = botId
                this.groupId = groupId
                this.userId = userId
            }
            entity.profile = profile
            entity.preferences = preferences
            entity.toRecord()
        }

    /**
     * 获取有效的事实记忆
     */
    fun getValidFacts(botId: String, groupId: String): List<FactsRecord> {
        return transaction {
            FactsEntity.find(FactsTable.validCondition(botId, groupId))
                .map { it.toRecord() }
        }
    }

    fun countAllValidFacts(): Int = transaction {
        FactsEntity.count(
            (FactsTable.validFrom lessEq CurrentDateTime) and
                    (FactsTable.validTo.isNull() or (FactsTable.validTo greater CurrentDateTime))
        ).toInt()
    }

    fun getAllFactGroups(): List<Pair<String, String>> = transaction {
        FactsTable
            .select(FactsTable.botId, FactsTable.groupId)
            .withDistinct(true)
            .map { it[FactsTable.botId] to it[FactsTable.groupId] }
            .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
    }

    fun getFactsForEntityRebuild(
        botId: String? = null,
        groupId: String? = null,
        onlyEmptyEntities: Boolean = true,
        includeInvalid: Boolean = false,
        limit: Int? = null
    ): List<FactsRecord> = transaction {
        val facts = FactsEntity.all()
            .map { it.toRecord() }
            .asSequence()
            .filter { fact -> botId == null || fact.botId == botId }
            .filter { fact -> groupId == null || fact.groupId == groupId }
            .filter { fact -> !onlyEmptyEntities || fact.entities.isEmpty() }
            .filter { fact -> includeInvalid || fact.validTo == null }
            .sortedBy { it.id }

        val limited = limit?.let { facts.take(it) } ?: facts
        limited.toList()
    }

    fun updateFactEntities(id: Int, entities: List<String>): FactsRecord? = transaction {
        FactsEntity.findById(id)?.apply {
            this.entities = entities
        }?.toRecord()
    }

    /**
     * 创建新的事实记忆
     */
    fun createFact(
        botId: String,
        groupId: String,
        keyword: String,
        description: String,
        entities: List<String>,
        subjects: String,
        scopeType: Scopes
    ): Int {
        return transaction {
            FactsEntity.new {
                this.botId = botId
                this.groupId = groupId
                this.keyword = keyword
                this.description = description
                this.entities = entities
                this.subjects = subjects
                this.scopeType = scopeType
                this.validFrom = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            }.id.value
        }
    }

    /**
     * 根据 ID 废弃事实
     */
    fun deprecateFactsById(botId: String, groupId: String, factId: Int, scopeType: Scopes): Boolean {
        return transaction {
            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            FactsTable.update({
                (FactsTable.id eq factId) and
                        (FactsTable.botId eq botId) and
                        (FactsTable.groupId eq groupId) and
                        (FactsTable.scopeType eq scopeType) and
                        (FactsTable.validTo.isNull())
            }) {
                it[FactsTable.validTo] = now
            } > 0
        }
    }

    // ==================== Facts 增强 ====================

    /** 根据 ID 查询 */
    fun getFactById(id: Int): FactsRecord? = transaction { FactsEntity.findById(id)?.toRecord() }

    fun updateFact(
        id: Int,
        keyword: String,
        description: String,
        entities: List<String>,
        subjects: String,
        scopeType: Scopes
    ): FactsRecord? =
        transaction {
            FactsEntity.findById(id)?.apply {
                this.keyword = keyword
                this.description = description
                this.entities = entities
                this.subjects = subjects
                this.scopeType = scopeType
                this.vectorId = null
            }?.toRecord()
        }

    /** 物理删除事实 */
    fun deleteFact(id: Int): Boolean = transaction {
        val fact = FactsEntity.findById(id)
        fact?.delete()
        fact != null
    }

    /** 更新向量 ID */
    fun updateFactVectorId(id: Int, vectorId: String) = transaction {
        FactsTable.update({ FactsTable.id eq id }) { it[FactsTable.vectorId] = vectorId }
    }

    fun updateFactVectorIds(values: List<Pair<Int, String>>) {
        if (values.isEmpty()) return
        transaction {
            values.forEach { (id, vectorId) ->
                FactsTable.update({ FactsTable.id eq id }) { it[FactsTable.vectorId] = vectorId }
            }
        }
    }

    /** 标记事实记忆最近一次被召回的时间。 */
    fun markFactsRecalled(ids: Collection<Int>) {
        if (ids.isEmpty()) return
        transaction {
            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            FactsTable.update({ FactsTable.id inList ids.toList() }) {
                it[FactsTable.lastRecalledAt] = now
            }
        }
    }

    /** 物理删除已经失效的事实记忆，并返回被删除记录用于清理向量。 */
    fun deleteExpiredFacts(cutoff: kotlinx.datetime.LocalDateTime): List<FactsRecord> = transaction {
        val expiredFacts = FactsEntity.find {
            FactsTable.validTo.isNotNull() and (FactsTable.validTo less cutoff)
        }.map { it.toRecord() }
        expiredFacts.forEach { fact -> FactsEntity.findById(fact.id)?.delete() }
        expiredFacts
    }

    /** 物理删除长期未被召回的有效事实记忆，并返回被删除记录用于清理向量。 */
    fun deleteStaleUnrecalledFacts(cutoff: kotlinx.datetime.LocalDateTime): List<FactsRecord> = transaction {
        val staleFacts = FactsEntity.find {
            (FactsTable.validFrom lessEq CurrentDateTime) and
                    (FactsTable.validTo.isNull() or (FactsTable.validTo greater CurrentDateTime)) and
                    (
                            ((FactsTable.lastRecalledAt.isNull()) and (FactsTable.createdAt less cutoff)) or
                                    (FactsTable.lastRecalledAt less cutoff)
                            )
        }.map { it.toRecord() }
        staleFacts.forEach { fact -> FactsEntity.findById(fact.id)?.delete() }
        staleFacts
    }

    /** 物理删除用户画像 */
    fun deleteUserProfile(botId: String, groupId: String, userId: String): Boolean = transaction {
        val entity = UserProfileEntity.find {
            (UserProfileTable.botId eq botId) and
                    (UserProfileTable.groupId eq groupId) and
                    (UserProfileTable.userId eq userId)
        }.firstOrNull()
        entity?.delete()
        entity != null
    }

    /** 查询未处理消息 */
    fun getUnprocessedMessages(
        botId: String,
        groupId: String,
        userId: String?,
        lastHistoryId: Int,
        limit: Int
    ): List<HistoryRecord> = transaction {
        val query = HistoryEntity.find {
            (HistoryTable.botId eq botId) and
                    (HistoryTable.groupId eq groupId) and
                    (HistoryTable.id greater lastHistoryId)
        }.orderBy(HistoryTable.id to SortOrder.ASC).limit(limit)

        if (userId != null) {
            query.filter { it.userId == userId }.map { it.toRecord() }
        } else {
            query.map { it.toRecord() }
        }
    }
}
