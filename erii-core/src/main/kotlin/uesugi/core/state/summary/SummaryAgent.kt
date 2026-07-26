package uesugi.core.state.summary

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.StructureFixingParser
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.params.LLMParams
import kotlinx.datetime.format
import kotlinx.serialization.Serializable
import uesugi.common.LLMModelChoice
import uesugi.common.data.HistoryRecord
import uesugi.common.toolkit.DateTimeFormat
import uesugi.common.toolkit.logger
import uesugi.common.toolkit.ref
import uesugi.core.message.history.asLlmPrompt
import kotlin.time.ExperimentalTime

@Serializable
data class SummaryAnalysis(
    val timeRange: String,
    val content: String,
    val keyPoints: List<String>,
    val emotionalTone: String,
    val participantIds: List<String>,
    val messageCount: Int
)

class SummaryAgent internal constructor(
    private val promptExecutorProvider: () -> PromptExecutor
) {

    constructor() : this({ ref<PromptExecutor>().value })

    companion object {
        private val log = logger()
    }

    @OptIn(ExperimentalTime::class)
    suspend fun generateSummary(
        messages: List<HistoryRecord>,
        groupId: String,
        previousSummaryContext: String? = null
    ): SummaryAnalysis {
        log.debug("Start generating summary, groupId=$groupId, message count=${messages.size}")

        val prompt = prompt("__memory_summary__", LLMParams(maxTokens = 65536)) {
            system(
                """
                # Role
                你是一名专业的社群对话分析师，拥有极强的信息归纳与上下文理解能力。

                # Workflow
                请按照以下步骤处理输入的群聊记录：
                1. **数据清洗**：剔除无意义的语气词、重复刷屏、系统消息。
                2. **话题聚类**：识别对话中并行发生的多个话题（如有），聚焦于最核心的主题。
                3. **因果分析**：识别对话的触发点（Trigger）和最终结论（Resolution）。
                4. **承接上文**：若提供了一段摘要，请将其作为背景参考，关注本段对话相较前一段的延续、转折或新增主题，避免重复总结已被覆盖的内容。
                5. **生成输出**：基于上述分析，生成符合要求的JSON。

                # Output Fields Spec
                1. **timeRange**: 精确覆盖首尾消息的时间，格式 "yyyy-MM-dd HH:mm"。
                2. **content**:
                   - 必须包含：讨论背景、核心冲突/观点、达成的一致或遗留问题。
                   - 保留具体人物、话题、争议点、结论和后续悬而未决的问题。
                   - 风格：清晰、信息密度高、非口语化。字数控制在300~600字。
                3. **keyPoints**:
                   - 提取5-8个"信息胶囊"。
                   - 格式示例："需求变更：确认将登录页改为蓝色主题"。
                   - 避免泛泛而谈（如"讨论了需求"是无效的，要写明"讨论了什么需求"）。
                   - 覆盖主要话题、关键决策、分歧点、待办/后续问题；消息较少时只输出有证据支撑的要点。
                4. **emotionalTone**: 准确判断整体氛围（如：焦虑、欢快、激烈争论、按部就班）。
                5. **participantIds**: 参与有效发言的用户ID列表（去重）。
                6. **messageCount**: 原始消息总数。

                # Output Format
                【重要】请仅输出**一个**纯JSON字符串，不包含任何其他文字、解释或标记。
                """.trimIndent()
            )

            val msg = messages.joinToString("\n") { it.asLlmPrompt() }
            val startTime = messages.firstOrNull()?.createdAt?.format(DateTimeFormat) ?: "unknown"
            val endTime = messages.lastOrNull()?.createdAt?.format(DateTimeFormat) ?: "unknown"

            val previousBlock = previousSummaryContext
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    """
                    上一段摘要(供承接参考，不要重复总结其中已覆盖的内容):
                    $it

                    """.trimIndent()
                }
                ?: ""

            user {
                text(
                    """
                    根据以下群聊记录生成对话摘要。请遵循系统消息中的分析步骤，对消息进行数据清洗、话题聚类和因果分析，输出结构化JSON。

                    数据如下：
                    """.trimIndent()
                )
                text(
                    """
                    ${previousBlock}时间范围: $startTime ~ $endTime

                    历史消息:
                    $msg

                    请生成对话摘要。
                    """.trimIndent()
                )
            }
        }

        val promptExecutor = promptExecutorProvider()

        val result = promptExecutor.executeStructured<SummaryAnalysis>(
            prompt = prompt,
            model = LLMModelChoice.Pro,
            fixingParser = StructureFixingParser(
                model = LLMModelChoice.Lite,
                retries = 2
            )
        )

        return result.getOrThrow().data.also {
            log.debug("Summary generation completed, groupId=$groupId")
        }
    }
}
