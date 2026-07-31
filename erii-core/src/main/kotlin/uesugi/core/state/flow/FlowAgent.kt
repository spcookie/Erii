package uesugi.core.state.flow

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.StructureFixingParser
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.params.LLMParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.format
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.core.context.GlobalContext
import uesugi.common.BotManage
import uesugi.common.EventBus
import uesugi.common.LLMModelChoice
import uesugi.common.toolkit.ConfigHolder
import uesugi.common.toolkit.DateTimeFormat
import uesugi.common.toolkit.logger
import kotlin.time.ExperimentalTime

enum class ChargeEventType {
    CoreInterest,
    GroupResonance,
    DeepReply,
    ContinuousInteraction
}

enum class DrainEventType {
    TopicInterrupt,
    Negative,
    RepeatTopic,
    LowActivity
}

@Serializable
@SerialName("TopicAnalysis")
@LLMDescription("话题分析结果，判断对话是否围绕当前话题")
data class TopicAnalysis(
    @property:LLMDescription("是否在当前话题上，true表示仍在讨论当前话题")
    val isOnTopic: Boolean,
    @property:LLMDescription("话题漂移程度，0.0-1.0，值越大表示偏离越严重")
    val topicDriftLevel: Double,
    @property:LLMDescription("修正后的话题描述，用于下一轮心流判定")
    val revisedTopic: String
)

@Serializable
@SerialName("InterestMatch")
@LLMDescription("兴趣匹配分析，判断是否命中机器人的核心兴趣领域")
data class InterestMatch(
    @property:LLMDescription("是否命中核心兴趣，true表示当前对话涉及机器人感兴趣的话题")
    val hit: Boolean,
    @property:LLMDescription("兴趣匹配分数，1.0-50.0，值越大表示匹配度越高")
    val score: Double,
    @property:LLMDescription("匹配的兴趣关键词列表，例如：['并发控制', '系统设计']")
    val matchedReasons: List<String>
)

@Serializable
@SerialName("InteractionQuality")
@LLMDescription("互动质量分析，评估对话的深度和质量")
data class InteractionQuality(
    @property:LLMDescription("是否存在深度回复，true表示有分析性观点或深入追问")
    val deepReplies: Boolean,
    @property:LLMDescription("判断理由的文字说明")
    val reason: String
)

@Serializable
@SerialName("GroupResonance")
@LLMDescription("群体共鸣分析，判断多人是否围绕相似话题展开讨论")
data class GroupResonance(
    @property:LLMDescription("是否存在群体共鸣，true表示多人围绕相似语义话题讨论")
    val exists: Boolean,
    @property:LLMDescription("参与讨论的人数")
    val participants: Int,
    @property:LLMDescription("情绪PAD的 arousal，-1.0-1.0，值越大表示唤醒度越强")
    val arousal: Double
)

@Serializable
@SerialName("NegativeSignals")
@LLMDescription("负面信号检测，识别对话中的负面刺激")
data class NegativeSignals(
    @property:LLMDescription("是否存在负面信号，true表示检测到负面内容")
    val exists: Boolean,
    @property:LLMDescription("负面信号类型列表，例如：['争吵', '攻击性语言', '冷嘲热讽']")
    val types: List<String>
)

@Serializable
@SerialName("Repetition")
@LLMDescription("重复话题检测，判断是否在无信息增量地重复讨论")
data class Repetition(
    @property:LLMDescription("是否存在重复，true表示检测到无信息增量的重复")
    val exists: Boolean,
    @property:LLMDescription("重复置信度，0.0-1.0，值越大表示重复程度越高")
    val confidence: Double
)

@Serializable
@SerialName("FlowSuggestions")
@LLMDescription("心流事件建议，指示应该触发哪些心流增量或消耗事件")
data class FlowSuggestions(
    @property:LLMDescription("应触发的心流增量事件类型列表")
    val shouldCharge: List<ChargeEventType>,
    @property:LLMDescription("应触发的心流消耗事件类型列表")
    val shouldDrain: List<DrainEventType>
)

@Serializable
@SerialName("FlowAnalysisResult")
@LLMDescription("群聊心流状态分析的完整结果")
data class FlowAnalysisResult(
    @property:LLMDescription("话题分析结果")
    val topicAnalysis: TopicAnalysis,
    @property:LLMDescription("兴趣匹配分析")
    val interestMatch: InterestMatch,
    @property:LLMDescription("互动质量分析")
    val interactionQuality: InteractionQuality,
    @property:LLMDescription("群体共鸣分析")
    val groupResonance: GroupResonance,
    @property:LLMDescription("负面信号检测")
    val negativeSignals: NegativeSignals,
    @property:LLMDescription("重复话题检测")
    val repetition: Repetition,
    @property:LLMDescription("心流事件触发建议")
    val flowSuggestions: FlowSuggestions
)

data class FlowMessage(
    val id: Int,
    val groupId: String,
    val userId: String,
    val time: LocalDateTime,
    val content: String
) {
    fun asLlmPrompt() = "[ID:$id $userId ${time.format(DateTimeFormat)}] $content"
}

class FlowAgent {
    companion object {
        private val log = logger()
    }

    @OptIn(ExperimentalTime::class)
    suspend fun analysis(messages: List<FlowMessage>, botId: String, groupId: String): Boolean {
        if (messages.isEmpty()) return true

        val promptExecutor by GlobalContext.get().inject<PromptExecutor>()

        val messagesText = messages.joinToString("\n") { it.asLlmPrompt() }

        val currentTopic = loadCurrentTopic(botId, groupId)

        val botInterests = BotManage.getBot(botId).role.character

        val prompt = prompt("__flow_analysis__", LLMParams(maxTokens = 65536)) {
            system(
                """
                你是一名"群聊对话状态分析器"，用于为 AI 机器人计算心流状态。
                
                你的任务是：
                - 判断对话是否仍然围绕当前话题
                - 判断是否命中机器人的核心兴趣
                - 判断互动质量、群体共鸣、负面刺激等
                - 输出结构化 JSON，用于程序直接消费
                
                重要约束：
                1. 群体共鸣 ≠ 多人说话，而是【多人围绕相似语义话题】
                2. 重复话题指"无信息增量的重复"，不是正常延续
                3. 输出必须是 **严格 JSON**，不要任何额外说明文字
                
                输出 JSON 结构
                """.trimIndent()
            )

            user {
                text(
                    """
                    分析群聊心流状态。根据系统消息中的指示判断：对话是否围绕当前话题、是否命中核心兴趣、互动质量、群体共鸣、负面刺激和重复程度，输出结构化JSON。

                    数据如下：
                    """.trimIndent()
                )
                text(
                    """
                    【当前话题（上一轮总结）】
                    $currentTopic

                    【机器人的核心兴趣领域】
                    $botInterests

                    【最近的群聊消息】
                    以下是最近的群聊消息，按时间顺序排列：
                    $messagesText
                    """.trimIndent()
                )
            }
        }

        return try {
            val response = promptExecutor.executeStructured<FlowAnalysisResult>(
                prompt,
                model = LLMModelChoice.Pro,
                fixingParser = StructureFixingParser(
                    model = LLMModelChoice.Lite,
                    retries = 2
                )
            )

            val result = response.getOrThrow().data

            persistAnalysisResult(botId, groupId, result)

            triggerFlowEvents(result, botId, groupId)

            log.info("Flow analysis completed, botId=$botId, groupId=$groupId, $result")
            true
        } catch (e: Exception) {
            log.error("Flow analysis failed, groupId=$groupId", e)
            false
        }
    }

    private fun triggerFlowEvents(result: FlowAnalysisResult, botId: String, groupId: String) {
        val tuning = ConfigHolder.getStateTuning().flow
        result.flowSuggestions.shouldCharge
            .distinct()
            .forEach { eventType ->
                when (eventType) {
                    ChargeEventType.CoreInterest -> if (result.interestMatch.hit) EventBus.postAsync(
                        CoreInterestEvent(
                            botId,
                            groupId,
                            (result.interestMatch.score / 50.0).coerceIn(0.0, 1.0)
                        )
                    )

                    ChargeEventType.GroupResonance -> if (
                        result.groupResonance.exists &&
                        result.groupResonance.participants >= 2
                    ) EventBus.postAsync(
                        GroupResonanceEvent(
                            botId,
                            groupId,
                            result.groupResonance.arousal.coerceIn(-1.0, 1.0)
                        )
                    )

                    ChargeEventType.DeepReply -> if (result.interactionQuality.deepReplies) EventBus.postAsync(
                        DeepReplyEvent(botId, groupId, tuning.deepReplyBaseCharge)
                    )
                    ChargeEventType.ContinuousInteraction -> EventBus.postAsync(
                        ContinuousInteractionEvent(
                            botId,
                            groupId
                        )
                    )
                }
            }

        val drainEvents = result.flowSuggestions.shouldDrain.toMutableSet()
        if (!result.topicAnalysis.isOnTopic && result.topicAnalysis.topicDriftLevel > 0.6) {
            drainEvents.add(DrainEventType.TopicInterrupt)
        }

        if (result.negativeSignals.exists) {
            drainEvents.add(DrainEventType.Negative)
        }

        if (result.repetition.exists && result.repetition.confidence > 0.7) {
            drainEvents.add(DrainEventType.RepeatTopic)
        }

        drainEvents.forEach { eventType ->
            when (eventType) {
                DrainEventType.TopicInterrupt -> EventBus.postAsync(
                    TopicInterruptEvent(botId, groupId, tuning.topicInterruptPenalty)
                )

                DrainEventType.Negative -> EventBus.postAsync(NegativeEvent(botId, groupId, tuning.negativePenalty))
                DrainEventType.RepeatTopic -> EventBus.postAsync(
                    RepeatTopicEvent(botId, groupId, tuning.repeatTopicPenalty)
                )

                DrainEventType.LowActivity -> EventBus.postAsync(
                    LowActivityEvent(
                        botId,
                        groupId,
                        tuning.lowActivityPenalty
                    )
                )
            }
        }
    }

    private fun loadCurrentTopic(botId: String, groupId: String): String {
        return transaction {
            FlowStateEntity.find {
                (FlowStateTable.botId eq botId) and (FlowStateTable.groupId eq groupId)
            }.firstOrNull()?.currentTopic ?: ""
        }
    }

    private suspend fun persistAnalysisResult(botId: String, groupId: String, result: FlowAnalysisResult) {
        withContext(Dispatchers.IO) {
            transaction {
                val flowState = FlowStateEntity.find {
                    (FlowStateTable.botId eq botId) and (FlowStateTable.groupId eq groupId)
                }.orderBy(FlowStateTable.lastProcessedAt to SortOrder.DESC).firstOrNull()

                flowState?.currentTopic = result.topicAnalysis.revisedTopic
            }
        }
    }

}
