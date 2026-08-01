package uesugi.core.component.llm.client.openai

import ai.koog.prompt.executor.clients.openai.models.OpenAIChatCompletionResponse
import ai.koog.prompt.message.Message
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 将 OpenAI 特有的缓存和推理 token 明细补充到 Koog 响应 metadata。 */
internal fun addOpenAIUsageMetadata(
    response: OpenAIChatCompletionResponse,
    assistants: List<Message.Assistant>,
): List<Message.Assistant> {
    val usage = response.usage ?: return assistants
    val cachedTokens = usage.promptTokensDetails?.cachedTokens
    val reasoningTokens = usage.completionTokensDetails?.reasoningTokens
    if (cachedTokens == null && reasoningTokens == null) return assistants

    val extraMetadata = buildJsonObject {
        if (cachedTokens != null) put("cached_tokens", cachedTokens)
        if (reasoningTokens != null) put("reasoning_tokens", reasoningTokens)
    }
    return assistants.map { assistant ->
        val metadata = JsonObject(assistant.metaInfo.metadata.orEmpty() + extraMetadata)
        assistant.copy(metaInfo = assistant.metaInfo.copy(metadata = metadata))
    }
}
