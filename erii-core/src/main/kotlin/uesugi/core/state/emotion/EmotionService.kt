package uesugi.core.state.emotion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import uesugi.common.BotManage
import uesugi.common.EventBus
import uesugi.common.data.EmotionalTendencies
import uesugi.common.data.PAD
import uesugi.common.toolkit.ConfigHolder
import uesugi.common.toolkit.EmotionTuningConfig
import uesugi.common.toolkit.logger
import uesugi.core.message.history.orEmptyTruncatedHistoryContent
import kotlin.time.Clock

object BehaviorMapper {

    fun fromEmotion(emotion: EmotionalTendencies): BehaviorProfile =
        when (emotion) {

            EmotionalTendencies.JOY,
            EmotionalTendencies.OPTIMISM ->
                BehaviorProfile(
                    emotion = emotion,
                    tone = Tone.FRIENDLY,
                    aggressiveness = Aggressiveness.NONE,
                    emojiLevel = EmojiLevel.HIGH,
                )

            EmotionalTendencies.RELAXATION,
            EmotionalTendencies.MILDNESS ->
                BehaviorProfile(
                    emotion = emotion,
                    tone = Tone.GENTLE,
                    aggressiveness = Aggressiveness.NONE,
                    emojiLevel = EmojiLevel.LOW,
                )

            EmotionalTendencies.BOREDOM ->
                BehaviorProfile(
                    emotion = emotion,
                    tone = Tone.NEUTRAL,
                    aggressiveness = Aggressiveness.TEASING,
                    emojiLevel = EmojiLevel.NONE,
                )

            EmotionalTendencies.SADNESS,
            EmotionalTendencies.ANXIETY ->
                BehaviorProfile(
                    emotion = emotion,
                    tone = Tone.LOW_ENERGY,
                    aggressiveness = Aggressiveness.NONE,
                    emojiLevel = EmojiLevel.LOW,
                )

            EmotionalTendencies.FEAR ->
                BehaviorProfile(
                    emotion = emotion,
                    tone = Tone.NEUTRAL,
                    aggressiveness = Aggressiveness.NONE,
                    emojiLevel = EmojiLevel.NONE,
                )

            EmotionalTendencies.CONTEMPT,
            EmotionalTendencies.DISGUST,
            EmotionalTendencies.RESENTMENT,
            EmotionalTendencies.HOSTILITY ->
                BehaviorProfile(
                    emotion = emotion,
                    tone = Tone.IRONIC,
                    aggressiveness = Aggressiveness.ABSTRACT_SARCASM,
                    emojiLevel = EmojiLevel.LOW,
                )

            EmotionalTendencies.SURPRISE ->
                BehaviorProfile(
                    emotion = emotion,
                    tone = Tone.FRIENDLY,
                    aggressiveness = Aggressiveness.NONE,
                    emojiLevel = EmojiLevel.MEDIUM,
                )

            EmotionalTendencies.DEPENDENCE ->
                BehaviorProfile(
                    emotion = emotion,
                    tone = Tone.GENTLE,
                    aggressiveness = Aggressiveness.NONE,
                    emojiLevel = EmojiLevel.LOW,
                )

        }
}

class BehaviorAnalysis(
    private val currentEmotion: PAD?,
    private val currentMood: PAD?,
    private val baseLine: EmotionalTendencies? = null,
    private val tuning: EmotionTuningConfig = EmotionTuningConfig()
) {
    fun decideEmotion(currentStimulus: Stimulus, retention: Retention): Emotion {
        val r = when (retention) {
            Retention.HIGH -> tuning.emotionRetentionHigh
            Retention.MEDIUM -> tuning.emotionRetentionMedium
            Retention.LOW -> tuning.emotionRetentionLow
        }
        val oldEmotion = currentEmotion
        return if (oldEmotion == null) {
            if (baseLine != null) baseLine.pad * r + currentStimulus * (1 - r) else currentStimulus
        } else {
            oldEmotion * r + currentStimulus * (1 - r)
        }
    }

    fun decideMood(emotion: Emotion): Mood {
        val oldMood = currentMood
        val retention = tuning.moodRetention
        val influence = tuning.moodEmotionInfluence
        return if (oldMood == null) {
            if (baseLine != null) baseLine.pad * retention + emotion * influence * (1 - retention)
            else emotion * influence
        } else {
            oldMood * retention + emotion * influence * (1 - retention)
        }
    }

    fun decideBehavior(emotion: Emotion, mood: Mood, applyEmotion: Boolean = true): BehaviorProfile {
        val target = if (applyEmotion) emotion else mood
        val emotional = EmotionalTendencies.findClosest(target)
        return BehaviorMapper.fromEmotion(emotional)
    }
}

class EmotionService(
    private val emotionRepository: EmotionRepository
) {

    private val log = logger()

    /**
     * 处理单个群组的情绪分析
     */
    suspend fun analyzeGroupEmotion(
        currentBotId: String,
        groupId: String,
        batchLimit: Int = 200,
        minimumMessages: Int = 11,
        force: Boolean = false
    ): Int? {
        val bot = BotManage.getBot(currentBotId)
        val baseLine = bot.role.emoticon
        val tuning = ConfigHolder.getStateTuning().emotion
        val maxMessageLength = ConfigHolder.getAgentMaxMessageLength()

        // 获取当前情绪状态和新消息
        val emotionEntity = withContext(Dispatchers.IO) {
            emotionRepository.getLatestEmotion(currentBotId, groupId)
        }

        val lastProcessedId = emotionEntity?.historyMessageProcessed ?: 0

        // 获取上下文消息
        val contextHistories = if (lastProcessedId > 0) {
            withContext(Dispatchers.IO) {
                emotionRepository.getContextMessages(currentBotId, groupId, lastProcessedId, 30)
            }
        } else {
            emptyList()
        }

        // 获取新消息
        val historyEntities = withContext(Dispatchers.IO) {
            emotionRepository.getLatestNewMessages(currentBotId, groupId, lastProcessedId, batchLimit)
        }

        // 检查消息数量
        if (historyEntities.isEmpty() || (!force && historyEntities.size < minimumMessages)) {
            return null
        }

        // 对旧情绪状态进行时间衰减（analyze 路径也要考虑两次分析之间的时间差）
        val (decayedEmotion, decayedMood) = if (emotionEntity != null) {
            withContext(Dispatchers.IO) {
                emotionRepository.decayEntity(emotionEntity, baseLine.pad, minSeconds = 60)
            }
        } else {
            null to null
        }

        // 转换为 GMessage
        val allHistories = contextHistories + historyEntities
        val gMessages = allHistories.map {
            GMessage(
                serial = it.id.value,
                userId = it.userId,
                role = if (it.userId == currentBotId) GMessage.Role.SELF else GMessage.Role.OTHER,
                time = it.createdAt,
                content = it.content.orEmptyTruncatedHistoryContent(maxMessageLength)
            )
        }

        // 调用 LLM 分析情感刺激值
        val stimulus = analyzeStimulus(gMessages, baseLine, decayedMood)

        // 创建行为分析器（使用衰减后的状态）
        val behaviorAnalysis = BehaviorAnalysis(
            currentEmotion = decayedEmotion,
            currentMood = decayedMood,
            baseLine = baseLine,
            tuning = tuning
        )

        // 确定保留等级（消息越多，旧情绪保留越少）
        val retention = when {
            historyEntities.size > 150 -> Retention.LOW
            historyEntities.size > 80 -> Retention.MEDIUM
            else -> Retention.HIGH
        }

        // 计算新的情绪和心情
        val emotion = behaviorAnalysis.decideEmotion(stimulus, retention)
        val mood = behaviorAnalysis.decideMood(emotion)

        // 计算行为表现
        val behaviorProfile = behaviorAnalysis.decideBehavior(emotion, mood)

        // 保存情绪状态
        val maxHistoryId = historyEntities.maxOf { it.id.value }
        withContext(Dispatchers.IO) {
            emotionRepository.saveEmotion(
                botId = currentBotId,
                groupId = groupId,
                emotionalTendency = behaviorProfile.emotion,
                stimulus = stimulus,
                emotion = emotion,
                mood = mood,
                behavior = behaviorProfile,
                historyMessageProcessed = maxHistoryId
            )
        }

        log.info("Saved emotion for group $groupId with emotion $emotion, mood $mood and behavior $behaviorProfile")

        // 发送事件
        EventBus.postAsync(EmotionChangeEvent(currentBotId, groupId, emotion))
        return maxHistoryId
    }

    /**
     * 处理单个群组的情绪衰减
     * 衰减时更新现有记录（不创建新记录），使 createdAt 保持为上次 analyze 的时间
     */
    suspend fun decayGroupEmotion(
        currentBotId: String,
        groupId: String
    ) {
        val currentEmotionEntity = withContext(Dispatchers.IO) {
            emotionRepository.getLatestEmotion(currentBotId, groupId)
        } ?: return
        val baseLine = BotManage.getBot(currentBotId).role.emoticon

        // 计算衰减（使用封装方法）
        val (emotion, mood) = withContext(Dispatchers.IO) {
            emotionRepository.decayEntity(currentEmotionEntity, baseLine.pad, minSeconds = 0)
        }

        // 计算行为（使用衰减后的 emotion/mood，不再重复计算）
        val tz = TimeZone.currentSystemDefault()
        val now = Clock.System.now()
        val hours = (now - currentEmotionEntity.createdAt.toInstant(tz)).inWholeHours.coerceAtLeast(0)
        val applyEmotion = hours < 1
        val behaviorAnalysis = BehaviorAnalysis(
            currentEmotion = emotion,
            currentMood = mood,
            baseLine = baseLine,
            tuning = ConfigHolder.getStateTuning().emotion
        )
        val behaviorProfile = behaviorAnalysis.decideBehavior(emotion, mood, applyEmotion = applyEmotion)

        // 更新现有记录（不创建新记录），stimulus 归零表示当前无外部刺激
        withContext(Dispatchers.IO) {
            emotionRepository.updateEmotion(
                entity = currentEmotionEntity,
                emotionalTendency = behaviorProfile.emotion,
                stimulus = PAD.ZERO,
                emotion = emotion,
                mood = mood,
                behavior = behaviorProfile
            )
        }

        // 发送事件
        EventBus.postAsync(EmotionChangeEvent(currentBotId, groupId, mood))
    }

    // ====== 原有查询方法 ======

    fun getCurrentBehaviorProfile(botId: String, groupId: String): BehaviorProfile? {
        return emotionRepository.getLatestEmotion(botId, groupId)?.behavior
    }

    fun getCurrentEmotion(botId: String, groupId: String): PAD? {
        return emotionRepository.getLatestEmotion(botId, groupId)?.emotion
    }
}
