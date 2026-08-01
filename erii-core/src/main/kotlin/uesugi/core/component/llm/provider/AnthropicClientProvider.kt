package uesugi.core.component.llm.provider

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.retry.RetryConfig
import ai.koog.prompt.executor.clients.retry.RetryingLLMClient
import ai.koog.prompt.llm.LLMProvider
import io.ktor.client.*
import uesugi.common.LLMModelChoice
import uesugi.common.toolkit.ConfigHolder
import uesugi.config.LLMClientProvider

class AnthropicClientProvider : LLMClientProvider {

    override val provider: LLMProvider = LLMProvider.Anthropic
    override val choiceKey: String = "ANTHROPIC"

    private val apiKey by lazy { ConfigHolder.getLlmAnthropicApiKey() }
    private val baseUrl by lazy { ConfigHolder.getLlmAnthropicBaseUrl() }

    override fun createClient(baseClient: HttpClient): RetryingLLMClient {
        val models = ConfigHolder.getLlmAnthropicModels()
        val modelVersionsMap = mapOf(
            LLMModelChoice.Lite to models["lite"],
            LLMModelChoice.Flash to models["flash"],
            LLMModelChoice.Pro to models["pro"],
        ).mapNotNull { (model, id) -> id?.let { model to it } }.toMap()

        val anthropicConfig = ConfigHolder.getLlmAnthropicClientConfig()
        return RetryingLLMClient(
            delegate = AnthropicLLMClient(
                apiKey = apiKey,
                settings = AnthropicClientSettings(
                    baseUrl = baseUrl,
                    modelVersionsMap = modelVersionsMap,
                    apiVersion = anthropicConfig.apiVersion,
                    messagesPath = anthropicConfig.messagesPath,
                    modelsPath = anthropicConfig.modelsPath
                ),
                httpClientFactory = KtorKoogHttpClient.Factory(baseClient),
            ),
            config = RetryConfig.CONSERVATIVE
        )
    }
}
