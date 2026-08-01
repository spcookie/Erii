package uesugi.config

import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.logging.*
import io.ktor.http.*
import uesugi.common.toolkit.ConfigHolder
import uesugi.core.component.llm.executor.DefaultParamPromptExecutor
import uesugi.core.component.llm.executor.FixingPromptExecutor
import uesugi.core.component.llm.executor.TokenUsagePromptExecutor
import uesugi.core.component.llm.executor.ToolCallRequestNormalizingPromptExecutor
import uesugi.core.component.llm.executor.ToolCallResponseRepairingPromptExecutor
import uesugi.core.component.usage.TokenUsageRepository

class LLMFactory(
    private val providers: List<LLMClientProvider>,
    private val tokenUsageRepository: TokenUsageRepository
) {

    fun promptExecutor(recordUsage: Boolean = true): PromptExecutor {
        val isDebug = System.getProperty("llm.request.debug")?.toBoolean()
            ?: System.getenv("LLM_REQUEST_DEBUG")?.toBoolean()
            ?: false

        val baseClient = getBaseClient(isDebug)
        val llmClients = providers
            .filter { it.isConfigured() }
            .associate { it.provider to it.createClient(baseClient) }

        val defaultParams = ConfigHolder.getLlmDefaultParams()
        val routedExecutor = ToolCallResponseRepairingPromptExecutor(
            ToolCallRequestNormalizingPromptExecutor(
                MultiLLMPromptExecutor(llmClients)
            )
        )
        val executor = DefaultParamPromptExecutor(
            FixingPromptExecutor(routedExecutor),
            defaultParams
        )
        return if (recordUsage) {
            TokenUsagePromptExecutor(executor, tokenUsageRepository)
        } else {
            executor
        }
    }

    fun getBaseClient(isDebug: Boolean): HttpClient = HttpClient {
        engine {
            if (ConfigHolder.isLlmProxyEnabled()) {
                val httpProxy = ConfigHolder.getProxyHttp()
                if (httpProxy != null) {
                    proxy = ProxyBuilder.http(httpProxy)
                }
            }
            if (isDebug) {
                install(Logging) {
                    logger = Logger.DEFAULT
                    level = LogLevel.ALL
                    sanitizeHeader { header -> header == HttpHeaders.Authorization }
                }
            }
        }
    }
}
