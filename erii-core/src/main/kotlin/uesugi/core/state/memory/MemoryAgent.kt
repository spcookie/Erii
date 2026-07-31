package uesugi.core.state.memory

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.StructureFixingParser
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.params.LLMParams
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import uesugi.common.LLMModelChoice
import uesugi.common.data.HistoryRecord
import uesugi.common.toolkit.logger
import uesugi.core.message.history.asLlmPrompt
import kotlin.time.ExperimentalTime

private fun normalizedSubjects(subjects: String): Set<String> =
    subjects.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

private fun normalizedKeyword(keyword: String): String = keyword.trim().lowercase()

internal fun isReplacementCandidate(newFact: ExtractedFact, existingFact: FactsRecord): Boolean {
    if (newFact.scope != existingFact.scopeType) return false
    if (normalizedKeyword(newFact.keyword) != normalizedKeyword(existingFact.keyword)) return false
    if (newFact.scope == Scopes.GROUP) return true
    val newSubjects = normalizedSubjects(newFact.subjects)
    val existingSubjects = normalizedSubjects(existingFact.subjects)
    return newSubjects.isNotEmpty() && existingSubjects.isNotEmpty() && newSubjects.intersect(existingSubjects).isNotEmpty()
}

internal fun selectConflictCandidates(
    newFacts: List<ExtractedFact>,
    existingFacts: List<FactsRecord>
): List<FactsRecord> = existingFacts.filter { existing ->
    newFacts.any { newFact -> isReplacementCandidate(newFact, existing) }
}

internal fun isDeleteBackedBySuccessfulAdd(
    candidate: FactsRecord,
    successfulAdds: List<ExtractedFact>
): Boolean = successfulAdds.any { newFact -> isReplacementCandidate(newFact, candidate) }

/**
 * 记忆代理 - 负责从历史消息中提取和生成各类记忆数据
 *
 * 记忆整理流程（4步）：
 * 1. LLM 提取事实（extractFacts）
 *    → 输出 {"facts": [{keyword, description, values, subjects, scope}]}
 * 2. LLM 冲突解决（resolveConflicts）
 *    → 仅对比同主体、同属性的候选记忆 → ADD / DELETE / NONE
 * 3. 批量执行决策（executeDecisions）
 *    → 数据库操作（增删）
 * 4. 统一向量同步（syncVectors）
 *    → 生成 Embedding → 写入 Vector Store
 */
class MemoryAgent(
    val memoryRepository: MemoryRepository,
    val factVectorStoreFactory: FactVectorStoreFactory,
    val factGraphStoreFactory: FactGraphStoreFactory,
    val promptExecutor: PromptExecutor
) {

    companion object {
        private val log = logger()
        private const val MAX_DELETES_PER_BATCH = 10
    }

    // ==================== 数据模型 ====================

    /**
     * 用户画像分析结果
     */
    @Serializable
    @LLMDescription("用户画像分析结果")
    data class UserProfileAnalysis(
        @property:LLMDescription("用户ID")
        val userId: String,
        @property:LLMDescription("用户性格、行为特征")
        val profile: String,
        @property:LLMDescription("兴趣偏好")
        val preferences: String
    )

    /**
     * 批量执行影响的事实集合（内部使用）
     */
    private data class AffectedFacts(
        val added: List<FactsRecord> = emptyList(),
        val deleted: List<DeletedFact> = emptyList()
    )

    private data class DeletedFact(val id: Int, val vectorId: String?)

    // ==================== 用户画像分析 ====================

    /**
     * 分析用户画像和偏好
     */
    suspend fun analyzeUserProfile(
        messages: List<HistoryRecord>,
        userProfileEntity: UserProfileRecord?
    ): UserProfileAnalysis {
        log.debug("Start analyzing user profile, userId=${messages.firstOrNull()?.userId}, message count=${messages.size}")

        val prompt = prompt("__memory_user_profile__", LLMParams(maxTokens = 65536)) {
            system(
                """
                你是一名用户行为分析专家。

                你的任务是根据用户在群聊中的历史消息，提取稳定、可复用的用户画像与兴趣偏好。

                # 分析对象

                单一用户

                数据来源：

                * 群聊历史消息
                * 可能包含引用
                * 可能包含复读
                * 可能包含玩梗
                * 可能包含机器人回复

                需要自动过滤噪声。

                # 核心原则

                仅基于用户真实发言进行分析。

                禁止：

                * 猜测现实身份
                * 猜测职业
                * 猜测学历
                * 猜测年龄
                * 猜测人格类型
                * 猜测心理状态

                不得使用：

                * 可能
                * 应该
                * 像是
                * 疑似
                * 大概

                所有结论必须能被消息直接证明。

                信息不足时输出空字符串：

                {
                  "profile": "",
                  "preferences": ""
                }

                # profile 定义

                profile 描述：

                用户在群聊中的行为模式和参与方式。

                关注：

                * 发言风格
                * 参与深度
                * 互动方式
                * 内容组织方式
                * 群内承担的角色

                不要描述具体兴趣。

                正确示例：

                * 经常提供完整解决方案，倾向于输出可执行内容
                * 遇到问题时习惯追问实现细节
                * 发言以技术讨论和问题排查为主
                * 经常回应他人问题并补充细节

                错误示例：

                * 喜欢 Kotlin
                * 喜欢摄影
                * 关注 AI

                这些属于 preferences。

                # preferences 定义

                preferences 描述：

                用户长期反复参与的话题和关注领域。

                必须满足以下至少一项：

                * 多次出现
                * 持续讨论
                * 主动发起相关话题
                * 长期关注

                不要记录一次性话题。

                正确示例：

                * Kotlin开发
                * Java后端
                * PostgreSQL
                * AI应用开发
                * 摄影设备
                * 游戏开发

                错误示例：

                * 今天讨论过的新闻
                * 一次性提到的游戏
                * 偶然问过的问题

                # 提取规则

                优先依据：

                * 发言频率
                * 话题重复度
                * 主动发起次数
                * 技术关键词密度
                * 持续讨论时长

                降低权重：

                * 表情
                * 复读
                * 引用
                * 单句附和
                * 无信息量回复

                # 输出要求

                profile：

                * 120~260字
                * 分维度描述行为模式，至少覆盖发言风格、互动方式、内容组织方式、群内角色中的2项
                * 保留能支撑结论的典型表现，但不要编造现实身份、职业、学历、年龄或心理状态
                * 不描述具体兴趣内容

                preferences：

                * 80~180字
                * 分维度描述长期关注领域、偏好的讨论深度、常见切入角度
                * 可使用顿号、分号或短句组织多个主题
                * 不记录一次性话题，不把临时热点写成长期偏好

                # 输出格式

                仅输出 JSON：

                {
                  "profile": "该用户经常参与问题排查和方案讨论，发言通常围绕具体需求、上下文约束和实现细节展开；互动时倾向于直接指出异常现象并要求给出可执行调整，内容组织较重视因果链路、边界条件和验证结果，在群内更像推动问题落地的人。",
                  "preferences": "长期关注Kotlin后端、AI应用开发、数据库与服务端架构等技术话题；偏好有明确场景、可验证结论和工程取舍的讨论，经常从实现成本、稳定性、上下文长度、工具调用效果等角度切入。"
                }

                不要输出任何解释、Markdown、注释或额外文字。
                """.trimIndent()
            )

            val msg = messages.joinToString("\n") { it.asLlmPrompt() }
            user {
                text(
                    """
                    分析以下用户的群聊行为特征和兴趣偏好。请基于消息内容提取可验证的行为特征，输出结构化JSON。

                    数据如下：
                    """.trimIndent()
                )
                text(buildString {
                    append("用户ID: ${messages.firstOrNull()?.userId ?: "unknown"}")
                    if (userProfileEntity != null) {
                        append("参考：")
                        append("之前分析的用户画像信息: ${userProfileEntity.profile}")
                        append("之前分析的用户偏好信息: ${userProfileEntity.preferences}")
                    }
                    append("历史消息：")
                    append(msg)
                    append("请分析该用户的画像和偏好，输出信息密度更高的当前最新版本。")
                    append("不要只写一句概括；在证据足够时保留多个稳定特征和多个长期偏好维度。")
                })
            }
        }

        val result = promptExecutor.executeStructured<UserProfileAnalysis>(
            prompt = prompt,
            model = LLMModelChoice.Pro,
            fixingParser = StructureFixingParser(
                model = LLMModelChoice.Lite,
                retries = 2
            )
        )

        return result.getOrThrow().data.also {
            log.debug("User profile analysis completed, userId=${it.userId}")
        }
    }

    // ==================== 记忆整理核心流程 ====================

    /**
     * 整理群记忆
     *
     * Step 1: LLM 提取事实 → FactExtractionResult
     * Step 2: LLM 冲突解决 → ConflictResolutionResult (ADD/DELETE/NONE)
     * Step 3: 批量执行决策 → 数据库操作
     * Step 4: 统一向量同步 → Embedding → Vector Store
     */
    suspend fun organize(
        botId: String,
        groupId: String,
        messages: List<HistoryRecord>
    ) {
        if (messages.isEmpty()) return

        // Step 1: 提取事实
        val extraction = try {
            extractFacts(messages)
        } catch (e: Exception) {
            log.error("Fact extraction failed, groupId=$groupId", e)
            return
        }
        log.info("Fact extraction completed, groupId=$groupId, extracted ${extraction.facts.size} facts")

        // Step 2: 获取已有事实
        val existingFacts = withContext(Dispatchers.IO) {
            memoryRepository.getValidFacts(botId, groupId)
        }

        if (extraction.facts.isEmpty()) {
            log.debug("No facts extracted; existing memories will not be reviewed for deletion, groupId=$groupId")
            return
        }

        // Step 3: 冲突解决（按作用域分开，仅比较同主体、同属性候选）
        val userFacts = extraction.facts.filter { it.scope == Scopes.USER }
        val groupFacts = extraction.facts.filter { it.scope == Scopes.GROUP }
        val existingUserFacts = selectConflictCandidates(userFacts, existingFacts)
        val existingGroupFacts = selectConflictCandidates(groupFacts, existingFacts)

        val userResolution = try {
            resolveConflicts(userFacts, existingUserFacts, messages)
        } catch (e: Exception) {
            log.error("User scope conflict resolution failed, groupId=$groupId", e)
            return
        }

        val groupResolution = try {
            resolveConflicts(groupFacts, existingGroupFacts, messages)
        } catch (e: Exception) {
            log.error("Group scope conflict resolution failed, groupId=$groupId", e)
            return
        }

        val allDecisions = userResolution.decisions + groupResolution.decisions

        val actionCounts = allDecisions.groupingBy { it.action }.eachCount()
        log.info("Conflict resolution completed, groupId=$groupId, decisions=$actionCounts")

        // Step 4: 批量执行决策
        val affectedFacts = executeDecisions(
            botId = botId,
            groupId = groupId,
            decisions = allDecisions,
            proposedFacts = extraction.facts,
            conflictCandidates = existingUserFacts + existingGroupFacts
        )

        // Step 4.5: 同步图存储（实体三元组）
        syncGraph(affectedFacts, botId, groupId)

        // Step 5: 统一向量同步
        syncVectors(botId, groupId, affectedFacts)

        log.info(
            "Memory organization completed, groupId=$groupId, " +
                    "added=${affectedFacts.added.size}, " +
                    "deleted=${affectedFacts.deleted.size}"
        )
    }

    // ==================== Step 1: 事实提取 ====================

    @OptIn(ExperimentalTime::class)
    private suspend fun extractFacts(messages: List<HistoryRecord>): FactExtractionResult {
        val prompt = prompt("__memory_fact_extract__", LLMParams(maxTokens = 65536)) {
            system(
                """
                你是一名长期记忆提取专家，负责从群聊消息中提取值得长期保存的记忆。
                ## 核心目标
                你的任务不是总结聊天内容，也不是提取用户画像。
                职业、学历、技能、偏好、关系等内容已经由独立画像系统维护。
                禁止将画像信息写入记忆。
                你的任务仅负责提取：
                1. 重要经历 2. 重大状态变化 3. 群体长期规则 4. 群体长期共识 5. 长期约定 6. 未来可能被引用的重要事件
                ---
                ## 默认原则
                默认不提取。
                只有明确符合记忆条件时才允许提取。
                如果无法确定是否属于长期记忆，则不要提取。
                ---
                ## 记忆准入条件
                提取的信息必须同时满足以下条件：
                * 一个月后仍然有价值 * 未来聊天中可能被引用 * 属于事件而非画像 * 属于事实而非观点 * 属于长期信息而非临时状态 * 不属于知识内容 * 不属于系统状态
                否则不要提取。
                ---
                # 允许提取
                ## 重要经历
                对用户人生、身份或长期状态产生影响的重要事件。
                ### 示例
                * 毕业 * 入职 * 离职 * 创业 * 搬家 * 结婚 * 离婚 * 分手 * 获奖 * 长期项目完成 * 重大事故 * 长期治疗经历 * 长期停学 * 长期休学
                ### 示例事实
                * 用户A从重庆搬到杭州工作 * 用户B获得省级程序设计竞赛一等奖 * 用户C完成创业项目并正式上线
                ---
                ## 重大状态变化
                仅限长期状态发生变化。
                ### 示例
                * 从学生变为上班族 * 从杭州搬到上海 * 从原公司离职 * 结婚 * 离婚 * 毕业 * 退学
                ### 示例事实
                * 用户A已经从XX公司离职 * 用户B毕业后开始工作 * 用户C搬迁至上海长期居住
                ---
                ## 群长期规则
                长期有效且被群体遵守的规则。
                ### 示例
                * 群内禁止广告 * 群内禁止剧透 * 群内禁止人身攻击 * 群内统一使用某机器人
                ---
                ## 群长期共识
                长期存在的群体约定或共识。
                ### 示例
                * 每周五固定活动 * 周年庆固定举办方式 * 长期执行的管理制度 * 群内默认使用某称呼
                ---
                ## 长期约定
                未来仍可能被引用或执行的约定。
                ### 示例
                * 群周年活动约定 * 长期维护计划 * 长期协作约定
                ---
                # 状态变化处理
                当消息中出现明确状态变化时，优先提取最新状态。
                关键词包括但不限于：
                * 已经 * 不再 * 不是了 * 换成 * 改成 * 搬到 * 离开 * 辞职 * 入职 * 毕业 * 退学 * 结婚 * 离婚 * 分手
                ### 示例
                原状态：用户A是学生
                新消息：我已经毕业开始工作了
                应提取：毕业
                而不是保留旧状态。
                ---
                # 严格禁止提取
                ## 系统信息
                * 机器人 * 插件 * 功能 * 命令 * 接口 * 模型 * 权限 * 配置 * BUG * 服务状态 * API信息
                ---
                ## 技术讨论
                * 代码 * SQL * 架构 * 开发方案 * 调试过程 * 性能优化 * 技术问答 * 技术教程
                ---
                ## 临时事件
                * 今天 * 明天 * 今晚 * 周末 * 最近 * 近期安排 * 一次性活动
                ---
                ## 临时状态
                * 上班 * 下班 * 睡觉 * 起床 * 吃饭 * 摸鱼 * 打游戏 * 看电影 * 看番
                ---
                ## 数值信息
                * 金币 * 余额 * 积分 * 排名 * 次数 * 价格 * 金价 * 股价 * 胜率 * 数量统计
                ---
                ## 知识内容
                * 问答 * 教程 * 百科 * 新闻 * 技术知识 * 科普内容
                ---
                ## 观点与情绪
                * 吐槽 * 抱怨 * 情绪表达 * 即时评价 * 主观观点 * 个人看法
                ---
                # 输出要求
                仅输出 JSON：
                {
                  "facts": [
                    {
                      "keyword": "重要经历",
                      "description": "用户A从重庆搬到杭州工作",
                      "entities": ["杭州", "重庆"],
                      "subjects": "A",
                      "scope": "user"
                    }
                  ]
                }
                如果没有符合条件的记忆：
                {
                  "facts": []
                }
                # 实体提取
                对于每条事实，从 description 和消息原文中提取涉及的实体。
                ## entities 字段格式
                JSON 字符串数组，包含实体名称，使用原文中的标准名称。
                示例：
                - "从杭州搬到了重庆" → ["杭州", "重庆"] - "入职了字节跳动" → ["字节跳动"] - "和张三一起做了项目" → ["张三"]
                没有实体时 entities 为空数组 []。
                不要输出任何解释、注释、Markdown 或额外文字。
                """.trimIndent()
            )

            val msgText = messages.joinToString("\n") { it.asLlmPrompt() }

            user {
                text(
                    """
                    请从以下群聊消息中提取事实：
                    """.trimIndent()
                )
                text(
                    """
                    $msgText
                    请输出 JSON 格式的事实列表。
                    """.trimIndent()
                )
            }
        }

        val result = promptExecutor.executeStructured<FactExtractionResult>(
            prompt = prompt,
            model = LLMModelChoice.Pro,
            fixingParser = StructureFixingParser(
                model = LLMModelChoice.Lite,
                retries = 2
            )
        )

        return result.getOrThrow().data
    }

    // ==================== Step 2: 冲突解决 ====================

    @OptIn(ExperimentalTime::class)
    private suspend fun resolveConflicts(
        newFacts: List<ExtractedFact>,
        existingFacts: List<FactsRecord>,
        messages: List<HistoryRecord>
    ): ConflictResolutionResult {
        if (newFacts.isEmpty() && existingFacts.isEmpty()) {
            return ConflictResolutionResult(decisions = emptyList())
        }

        val prompt = prompt("__memory_conflict_resolve__", LLMParams(maxTokens = 65536)) {
            system(
                """
                你是一名记忆维护专家。基于最新聊天消息，维护一组准确、不过时的记忆。

                ## 职责

                ### 1. 处理新提取的事实
                每条新事实与已有记忆对比：
                - ADD: 新信息，与已有记忆不冲突，添加
                - NONE: 与已有记忆完全重复，跳过

                ### 2. 替换冲突记忆
                DELETE 只能用于被新事实明确替换的候选旧记忆：
                - 必须同时输出对应的 ADD 新版本
                - 必须是同一主体、同一 scope、同一 keyword 属性
                - 仅仅没有在最新消息中再次提到，不代表旧记忆过时
                - 禁止独立清理、猜测失效或删除与新事实无关的记忆

                ## 规则
                - 同一属性值变化 → DELETE 旧 + ADD 新（两个决策）
                - 完全重复 → NONE

                ## 输出格式
                只输出 JSON 对象，包含 decisions 数组。每条决策：
                - action: ADD / DELETE / NONE
                - newFact: 新事实（ADD 时必填，否则可空）
                - existingFactId: 已有事实ID（DELETE 时必填）
                - reason: 原因（10字以内）
                """.trimIndent()
            )

            val newFactsText = newFacts.joinToString("\n") {
                "- keyword: ${it.keyword}, description: ${it.description}, entities: ${it.entities}, subjects: ${it.subjects}, scope: ${it.scope}"
            }.ifEmpty { "(no new extracted facts)" }

            val existingFactsText = existingFacts.joinToString("\n") {
                "ID: ${it.id} | keyword: ${it.keyword} | entities: ${it.entities} | subjects: ${it.subjects} | scope: ${it.scopeType} | createdAt: ${it.createdAt}"
            }.ifEmpty { "(no existing memories)" }

            val msgText = messages.joinToString("\n") { it.asLlmPrompt() }

            user {
                text(
                    """
                    ## 原始聊天消息（仅用于核对新旧事实是否明确冲突）
                    $msgText

                    ## 新提取的事实候选
                    $newFactsText

                    ## 与新事实同主体、同属性的候选已有记忆
                    $existingFactsText

                    请输出完整决策列表。DELETE 必须与同属性的 ADD 新版本同时出现；不要审查或删除候选列表之外的记忆。
                    """.trimIndent()
                )
            }
        }

        val result = promptExecutor.executeStructured<ConflictResolutionResult>(
            prompt = prompt,
            model = LLMModelChoice.Pro,
            fixingParser = StructureFixingParser(
                model = LLMModelChoice.Lite,
                retries = 2
            )
        )

        return result.getOrThrow().data
    }

    // ==================== Step 3: 批量执行决策 ====================

    private suspend fun executeDecisions(
        botId: String,
        groupId: String,
        decisions: List<MemoryDecision>,
        proposedFacts: List<ExtractedFact>,
        conflictCandidates: List<FactsRecord>
    ): AffectedFacts {
        val added = mutableListOf<FactsRecord>()
        val deleted = mutableListOf<DeletedFact>()
        val successfulAdds = mutableListOf<Pair<ExtractedFact, FactsRecord>>()
        val candidateById = conflictCandidates.associateBy { it.id }

        decisions.filter { it.action == MemoryAction.ADD }.forEach { decision ->
            val requestedFact = decision.newFact ?: return@forEach
            val proposedFact = proposedFacts.firstOrNull { it == requestedFact }
            if (proposedFact == null) {
                log.warn("Skipped memory ADD not present in extracted facts, groupId=$groupId, keyword=${requestedFact.keyword}")
                return@forEach
            }
            createAndFetchFact(botId, groupId, proposedFact)?.let { created ->
                added.add(created)
                successfulAdds.add(proposedFact to created)
            }
        }
        val successfulAddFacts = successfulAdds.map { it.first }

        val approvedDeletes = decisions
            .filter { it.action == MemoryAction.DELETE }
            .distinctBy { it.existingFactId }
            .mapNotNull { decision ->
                val existingId = decision.existingFactId
                val candidate = existingId?.let(candidateById::get)
                if (existingId == null || candidate == null) {
                    log.warn("Skipped memory DELETE outside conflict candidates, groupId=$groupId, factId=$existingId")
                    return@mapNotNull null
                }
                if (!isDeleteBackedBySuccessfulAdd(candidate, successfulAddFacts)) {
                    log.warn(
                        "Skipped memory DELETE without successful replacement ADD, " +
                                "groupId=$groupId, factId=$existingId, keyword=${candidate.keyword}"
                    )
                    return@mapNotNull null
                }
                decision to candidate
            }

        val deleteLimit = (successfulAdds.size * 2).coerceIn(0, MAX_DELETES_PER_BATCH)
        val deleteRatioUnsafe = conflictCandidates.size >= 5 && approvedDeletes.size * 2 > conflictCandidates.size
        if (approvedDeletes.size > deleteLimit || deleteRatioUnsafe) {
            log.warn(
                "Skipped unsafe memory DELETE batch, groupId=$groupId, requested=${approvedDeletes.size}, " +
                        "candidates=${conflictCandidates.size}, successfulAdds=${successfulAdds.size}"
            )
        } else {
            approvedDeletes.forEach { (decision, candidate) ->
                val deprecated = withContext(Dispatchers.IO) {
                    memoryRepository.deprecateFactsById(
                        botId = botId,
                        groupId = groupId,
                        factId = candidate.id,
                        scopeType = candidate.scopeType
                    )
                }
                if (deprecated) {
                    deleted.add(DeletedFact(candidate.id, candidate.vectorId))
                    log.info(
                        "Memory fact deprecated, groupId=$groupId, factId=${candidate.id}, " +
                                "keyword=${candidate.keyword}, reason=${decision.reason}"
                    )
                } else {
                    log.warn("Memory DELETE matched no current fact, groupId=$groupId, factId=${candidate.id}")
                }
            }
        }

        return AffectedFacts(added, deleted)
    }

    private suspend fun createAndFetchFact(
        botId: String,
        groupId: String,
        fact: ExtractedFact
    ): FactsRecord? {
        val id = withContext(Dispatchers.IO) {
            memoryRepository.createFact(
                botId, groupId,
                fact.keyword, fact.description,
                fact.entities, fact.subjects, fact.scope
            )
        }
        return withContext(Dispatchers.IO) {
            memoryRepository.getFactById(id)
        }
    }

    // ==================== Step 4: 统一向量同步 ====================

    private fun syncGraph(affectedFacts: AffectedFacts, botId: String, groupId: String) {
        for (added in affectedFacts.added) {
            factGraphStoreFactory.addFactEntities(added)
        }
        for (deleted in affectedFacts.deleted) {
            factGraphStoreFactory.removeFactEntities(deleted.id, botId, groupId)
        }
    }

    private suspend fun syncVectors(
        botId: String,
        groupId: String,
        affectedFacts: AffectedFacts
    ) {
        for (deleted in affectedFacts.deleted) {
            deleted.vectorId?.let { vectorId ->
                factVectorStoreFactory.deleteVector(vectorId, botId, groupId)
                log.debug("Old vector deleted, factId=${deleted.id}, vectorId=$vectorId")
            }
        }

        val factsToIndex = affectedFacts.added
        coroutineScope {
            factsToIndex.map { fact ->
                async {
                    val vectorId = factVectorStoreFactory.indexFact(fact)
                    withContext(Dispatchers.IO) {
                        memoryRepository.updateFactVectorId(fact.id, vectorId)
                    }
                    log.debug("Vector index created, factId=${fact.id}, vectorId=$vectorId")
                }
            }.awaitAll()
        }
    }
}
