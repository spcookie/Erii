package uesugi.core.component.llm.provider

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.retry.RetryConfig
import ai.koog.prompt.executor.clients.retry.RetryingLLMClient
import ai.koog.prompt.llm.LLMProvider
import io.ktor.client.*
import uesugi.common.toolkit.ConfigHolder
import uesugi.config.LLMClientProvider
import uesugi.core.component.llm.client.openai.OpenAICompatibleLLMClient

class OpenAIClientProvider : LLMClientProvider {

    override val provider: LLMProvider = LLMProvider.OpenAI
    override val choiceKey: String = "OPENAI"

    private val apiKey by lazy { ConfigHolder.getLlmOpenAIApiKey() }
    private val baseUrl by lazy { ConfigHolder.getLlmOpenAIBaseUrl() }

    override fun createClient(baseClient: HttpClient): RetryingLLMClient {
        val paths = ConfigHolder.getLlmOpenAIClientConfig()
        return RetryingLLMClient(
            delegate = OpenAICompatibleLLMClient(
                apiKey = apiKey,
                settings = OpenAIClientSettings(
                    baseUrl = baseUrl,
                    chatCompletionsPath = paths.chatCompletionsPath,
                    responsesAPIPath = paths.responsesAPIPath,
                    embeddingsPath = paths.embeddingsPath,
                    moderationsPath = paths.moderationsPath,
                    modelsPath = paths.modelsPath
                ),
                httpClientFactory = KtorKoogHttpClient.Factory(baseClient),
            ),
            config = RetryConfig.CONSERVATIVE
        )
    }
}
