package uesugi.core.component.search

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.StructureFixingParser
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.markdown.MarkdownContentBuilder
import ai.koog.prompt.params.LLMParams
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import uesugi.common.LLMModelChoice
import uesugi.common.extend.SearchResultItem
import uesugi.common.toolkit.logger
import uesugi.common.toolkit.ref
import kotlin.time.ExperimentalTime

object WebSearchTool : ToolSet {

    private val log = logger()

    @Serializable
    data class Input(
        @property:LLMDescription("搜索关键词。如果提供了 specificUrl，此项可为空。")
        val query: String? = null,
        @property:LLMDescription("需要直接读取内容的特定 URL。直接访问该链接。")
        val specificUrls: List<String>? = null,
        @property:LLMDescription("搜索结果数量 (1-5)。简单事实查询填 1-2；复杂话题研究/对比分析填 3-5。默认为 3。")
        val maxResults: Int? = null
    )

    /**
     * Query Rewrite 子 Agent 的输出结构
     */
    @Serializable
    data class SearchPlan(
        val queries: List<String>  // 优化后的搜索查询（最多使用前 3 个）
    )

    /**
     * Query Rewrite 子 Agent：将用户原始问题转化为高质量搜索查询
     */
    @OptIn(ExperimentalTime::class)
    private suspend fun rewriteQuery(originalQuery: String): SearchPlan {
        val promptExecutor by ref<PromptExecutor>()

        val rewritePrompt = prompt("__search_analysis__", LLMParams(maxTokens = 16384)) {
            system(
                """
                你是一个搜索查询优化专家。将用户的原始问题转化为 1-3 个高质量搜索引擎查询。
                
                规则：
                1. 提取核心关键词，去除无关词语
                2. 技术问题优先使用英文查询（搜索引擎英文资源覆盖更全）
                3. 模糊问题改写为具体明确的查询
                4. 对比分析类问题拆分为独立查询
                5. 添加有助于精确搜索的限定词（如 "best practice"、"official docs"）
                6. 每个查询简洁有力，不超过 10 个词
                7. 简单事实查询只需 1 个查询，复杂问题 2-3 个
                """.trimIndent()
            )
            user {
                text("原始问题：")
                text(originalQuery)
            }
        }

        val result = promptExecutor.executeStructured<SearchPlan>(
            prompt = rewritePrompt,
            model = LLMModelChoice.Flash,
            fixingParser = StructureFixingParser(
                model = LLMModelChoice.Lite,
                retries = 2
            )
        )

        return result.getOrThrow().data
    }

    /**
     * 构建富格式 Markdown 输出
     */
    private fun buildResultMarkdown(results: List<SearchResultItem>): String {
        return MarkdownContentBuilder().apply {
            if (results.isNotEmpty()) {
                results.forEachIndexed { index, item ->
                    header(3, "${index + 1}. ${item.title}")
                    line { text("**来源**: ${item.url}") }
                    line { text("**相关度**: ${"%.2f".format(item.score)}") }
                    line { text(item.content) }
                }
            } else {
                line { text("暂未搜索到内容") }
            }
        }.build()
    }

    @Tool
    @LLMDescription(
        """
        每当您需要搜索网络上的实时或外部信息时，必须使用此工具。
        一款功能与 Google 搜索完全一致的网页搜索 API。

        搜索策略：
        - 如果未返回有用结果，请尝试使用不同的关键词重新表述您的查询。

        使用方式：
        - query: 直接传入用户的原始问题或意图
        - specificUrls: 用户提供具体 URL 时使用，直接读取网页内容。
        - maxResults: 简单查询 1-2，普通问题 3，深度研究/对比分析 4-5

        结果定义：
        - 每条结果附带来源 URL 和相关度评分（0-1）
        - 评分越高表示与查询越相关，应据此评估信息可信度
    """
    )
    suspend fun webSearch(input: Input): String {
        val (query, specificUrls, maxResults) = input
        log.info(
            "LLM tool webSearch started: query={}, specificUrls={}, maxResults={}",
            query?.take(300),
            specificUrls?.map(::urlForLog),
            maxResults
        )

        return try {
            withContext(Dispatchers.IO) {
                coroutineScope {
                    val effectiveMaxResults = maxResults?.let { if (it > 5) 5 else it } ?: 3

                    // Step 1: Query Rewrite（仅对关键词搜索生效）
                    val searchQueries = if (!query.isNullOrBlank()) {
                        try {
                            val plan = rewriteQuery(query)
                            log.info(
                                "Query rewrite: original={} -> rewritten={}",
                                query.take(300),
                                plan.queries.map { it.take(300) }
                            )
                            plan.queries.take(3)
                        } catch (e: Exception) {
                            log.warn("Query rewrite failed, using original query: {}", query.take(300), e)
                            listOf(query)
                        }
                    } else {
                        emptyList()
                    }

                    // Step 2: 并行搜索
                    val searchService = SearchManager.get()
                    val allResults = if (searchQueries.isNotEmpty()) {
                        searchQueries.map { q ->
                            async { searchService.search(q, null, effectiveMaxResults) }
                        }.awaitAll().flatten()
                    } else {
                        emptyList()
                    }

                    // URL 直读结果
                    val urlResults = if (!specificUrls.isNullOrEmpty()) {
                        searchService.search(null, specificUrls, effectiveMaxResults)
                    } else {
                        emptyList()
                    }

                    // Step 3: 合并 + 去重 + 排序 + 截取
                    val mergedResults = (allResults + urlResults)
                        .distinctBy { it.url }
                        .sortedByDescending { it.score }
                        .take(effectiveMaxResults)

                    // Step 4: 信息聚合合成
                    val result = buildResultMarkdown(mergedResults)
                    log.info(
                        "LLM tool webSearch completed: queries={}, urls={}, results={}, resultChars={}",
                        searchQueries.size,
                        specificUrls?.size ?: 0,
                        mergedResults.size,
                        result.length
                    )
                    result
                }
            }
        } catch (e: Exception) {
            log.error(
                "LLM tool webSearch failed: query={}, specificUrls={}, maxResults={}",
                query?.take(300),
                specificUrls?.map(::urlForLog),
                maxResults,
                e
            )
            "搜索/抓取失败"
        }
    }

    private fun urlForLog(url: String): String = url
        .substringBefore('?')
        .substringBefore('#')
        .take(300)
}
