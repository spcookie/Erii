package uesugi.core.state.emotion

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uesugi.common.data.EmotionalTendencies
import uesugi.common.data.HistoryEntity
import uesugi.common.data.HistoryTable
import uesugi.common.data.PAD
import uesugi.common.toolkit.ConfigHolder
import uesugi.common.toolkit.logger
import kotlin.math.exp
import kotlin.math.ln
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * 情绪仓库 - 负责数据库操作
 */
class EmotionRepository {

    companion object {
        private val log = logger()
        private const val MAX_EMOTION_HISTORY_PER_GROUP = 100
    }

    /**
     * 查找需要分析的群组（新消息的群组）
     */
    fun findGroupsNeedAnalysis(botId: String): List<String> {
        return transaction {
            EmotionEntity.findRequiredAnalysisHistoryGroupIds(botId)
        }
    }

    /**
     * 查找不需要分析的群组（无新消息的群组）
     */
    fun findGroupsNotNeedAnalysis(botId: String, excludeGroups: List<String>): List<String> {
        return transaction {
            EmotionEntity.findNotAnalysisHistoryGroupIds(botId, excludeGroups)
        }
    }

    /**
     * 获取群组的最新情绪记录
     */
    fun getLatestEmotion(botId: String, groupId: String): EmotionEntity? {
        return transaction {
            EmotionEntity.find(
                (EmotionTable.groupId eq groupId) and (EmotionTable.botId eq botId)
            )
                .orderBy(EmotionTable.createdAt to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
        }
    }

    fun getLatestNewMessages(
        botId: String,
        groupId: String,
        lastProcessedId: Int,
        limit: Int = 200
    ): List<HistoryEntity> = transaction {
        HistoryEntity.find(
            (HistoryTable.botId eq botId) and
                    (HistoryTable.groupId eq groupId) and
                    (HistoryTable.id greater lastProcessedId)
        )
            .orderBy(HistoryTable.id to SortOrder.DESC)
            .limit(limit)
            .toList()
            .reversed()
    }

    /**
     * 获取上下文消息（用于情绪分析）
     */
    fun getContextMessages(botId: String, groupId: String, beforeId: Int, limit: Int = 100): List<HistoryEntity> {
        return transaction {
            HistoryEntity.find(
                (HistoryTable.botId eq botId) and
                        (HistoryTable.groupId eq groupId) and
                        (HistoryTable.id lessEq beforeId)
            )
                .orderBy(HistoryTable.id to SortOrder.DESC)
                .limit(limit)
                .toList()
                .reversed()
        }
    }

    /**
     * 保存情绪记录
     */
    fun saveEmotion(
        botId: String,
        groupId: String,
        emotionalTendency: EmotionalTendencies,
        stimulus: PAD,
        emotion: PAD,
        mood: PAD,
        behavior: BehaviorProfile,
        historyMessageProcessed: Int
    ) {
        transaction {
            EmotionEntity.new {
                this.botId = botId
                this.groupId = groupId
                this.emotionalTendency = emotionalTendency
                this.stimulus = stimulus
                this.emotion = emotion
                this.mood = mood
                this.behavior = behavior
                this.historyMessageProcessed = historyMessageProcessed
            }
            log.debug("群组 $groupId 情绪状态已保存, 处理到 historyId=$historyMessageProcessed")

            // 清理旧记录，每个 (botId, groupId) 保留最近 N 条
            val idsToKeep = EmotionEntity.find {
                (EmotionTable.botId eq botId) and (EmotionTable.groupId eq groupId)
            }
                .orderBy(EmotionTable.createdAt to SortOrder.DESC)
                .limit(MAX_EMOTION_HISTORY_PER_GROUP)
                .map { it.id.value }

            EmotionEntity.find {
                (EmotionTable.botId eq botId) and
                        (EmotionTable.groupId eq groupId) and
                        (EmotionTable.id notInList idsToKeep)
            }.forEach { it.delete() }
        }
    }

    /**
     * 更新现有情绪记录（用于衰减时，不创建新记录）
     */
    fun updateEmotion(
        entity: EmotionEntity,
        emotionalTendency: EmotionalTendencies,
        stimulus: PAD,
        emotion: PAD,
        mood: PAD,
        behavior: BehaviorProfile
    ) {
        transaction {
            entity.apply {
                this.emotionalTendency = emotionalTendency
                this.stimulus = stimulus
                this.emotion = emotion
                this.mood = mood
                this.behavior = behavior
                this.updatedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            }
            log.debug("群组 ${entity.groupId} 情绪状态已更新（衰减）")
        }
    }

    /**
     * 根据实体记录的更新时间计算时间衰减
     * @param minSeconds 最小时间差，小于此值则跳过衰减计算
     * @return Pair(衰减后的 emotion, 衰减后的 mood)
     */
    @OptIn(ExperimentalTime::class)
    fun decayEntity(entity: EmotionEntity, baseline: PAD, minSeconds: Long = 0): Pair<PAD, PAD> {
        val tz = TimeZone.currentSystemDefault()
        val now = Clock.System.now()
        val instant = entity.updatedAt.toInstant(tz)
        val seconds = (now - instant).inWholeSeconds.coerceAtLeast(0)
        return if (seconds > minSeconds) {
            calculateDecayEmotion(entity.emotion, entity.mood, seconds) to calculateDecayMood(
                entity.mood,
                baseline,
                seconds
            )
        } else {
            entity.emotion to entity.mood
        }
    }

    /**
     * 情绪衰减计算，向 mood 回弹
     */
    fun calculateDecayEmotion(emotion: PAD, mood: PAD, deltaSeconds: Long): PAD {
        val halfLife = ConfigHolder.getStateTuning().emotion.emotionHalfLifeSeconds.coerceAtLeast(1)
        val factor = decayFactor(halfLife, deltaSeconds)
        return decayToward(emotion, mood, factor)
    }

    /**
     * 心情衰减计算
     */
    fun calculateDecayMood(mood: PAD, baseline: PAD, deltaSeconds: Long): PAD {
        val halfLife = ConfigHolder.getStateTuning().emotion.moodHalfLifeSeconds.coerceAtLeast(1)
        val factor = decayFactor(halfLife, deltaSeconds)
        return decayToward(mood, baseline, factor)
    }

    private fun decayFactor(halfLifeSeconds: Long, deltaSeconds: Long): Double {
        val lambda = ln(2.0) / halfLifeSeconds
        return exp(-lambda * deltaSeconds)
    }

    private fun decayToward(current: PAD, target: PAD, factor: Double): PAD = PAD(
        p = target.p + (current.p - target.p) * factor,
        a = target.a + (current.a - target.a) * factor,
        d = target.d + (current.d - target.d) * factor
    )
}
