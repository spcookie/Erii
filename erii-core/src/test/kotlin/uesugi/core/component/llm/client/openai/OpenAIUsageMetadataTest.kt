package uesugi.core.component.llm.client.openai

import ai.koog.prompt.executor.clients.openai.base.models.CompletionTokensDetails
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIUsage
import ai.koog.prompt.executor.clients.openai.base.models.PromptTokensDetails
import ai.koog.prompt.executor.clients.openai.models.OpenAIChatCompletionResponse
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class OpenAIUsageMetadataTest {

    @Test
    fun `adds cache and reasoning token metadata`() {
        val response = OpenAIChatCompletionResponse(
            choices = emptyList(),
            created = 0,
            id = "response-id",
            model = "model-id",
            objectType = "chat.completion",
            usage = OpenAIUsage(
                promptTokensDetails = PromptTokensDetails(cachedTokens = 12),
                completionTokensDetails = CompletionTokensDetails(reasoningTokens = 5),
            ),
        )
        val assistant = Message.Assistant(
            content = "",
            metaInfo = ResponseMetaInfo(timestamp = Clock.System.now()),
        )

        val enriched = addOpenAIUsageMetadata(response, listOf(assistant)).single()

        assertEquals(12, enriched.metaInfo.metadata!!["cached_tokens"]!!.jsonPrimitive.int)
        assertEquals(5, enriched.metaInfo.metadata!!["reasoning_tokens"]!!.jsonPrimitive.int)
    }
}
