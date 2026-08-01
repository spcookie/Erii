package uesugi.core.component.llm.client.openai

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ai.koog.prompt.executor.clients.openai.base.models.OpenAITool
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolChoice
import ai.koog.prompt.executor.clients.openai.models.OpenAIChatCompletionResponse
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import uesugi.common.toolkit.logger

/** 处理 Koog 原生 OpenAI Client 尚未覆盖的请求、响应和 Usage 兼容逻辑。 */
internal class OpenAICompatibleLLMClient(
    apiKey: String,
    settings: OpenAIClientSettings,
    httpClientFactory: KoogHttpClient.Factory,
) : OpenAILLMClient(apiKey, settings, httpClientFactory) {

    private val log = logger()

    override fun serializeProviderChatRequest(
        messages: List<OpenAIMessage>,
        model: LLModel,
        tools: List<OpenAITool>?,
        toolChoice: OpenAIToolChoice?,
        params: LLMParams,
        stream: Boolean,
    ): String = super.serializeProviderChatRequest(
        // Koog 转换通用 Tool Call 时会额外序列化 args，发送前必须撤销这一层编码。
        messages = normalizeOpenAIToolCallRequest(messages),
        model = model,
        tools = tools,
        toolChoice = toolChoice,
        params = params,
        stream = stream,
    )

    override fun processProviderChatResponse(
        response: OpenAIChatCompletionResponse,
    ): List<Message.Assistant> {
        // 必须在调用 super 前修复，否则 Koog 转换 Tool Call 时会先解析坏 JSON 并抛出异常。
        val repairedResponse = repairOpenAIToolCallResponse(response) { toolName ->
            log.warn("Repaired malformed tool arguments returned by OpenAI-compatible provider: tool={}", toolName)
        }
        val assistants = super.processProviderChatResponse(repairedResponse)

        // Anthropic 原生客户端已经写入 usage metadata；这里只有 OpenAI 需要补充缓存和推理 token。
        return addOpenAIUsageMetadata(repairedResponse, assistants)
    }
}
