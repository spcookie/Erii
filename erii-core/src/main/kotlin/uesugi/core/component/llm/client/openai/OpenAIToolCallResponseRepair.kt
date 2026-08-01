package uesugi.core.component.llm.client.openai

import ai.koog.prompt.executor.clients.openai.base.models.OpenAIFunction
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolCall
import ai.koog.prompt.executor.clients.openai.models.OpenAIChatCompletionResponse
import ai.koog.prompt.executor.clients.openai.models.OpenAIChoice
import uesugi.core.component.llm.toolcall.ToolCallArgumentNormalizer

/**
 * 在 Koog 转换 OpenAI Provider 响应之前修复 Tool Call 参数。
 *
 * OpenAI 协议把 `function.arguments` 定义为一个包含 JSON 的字符串。Koog 在把
 * [OpenAIChatCompletionResponse] 转换为通用 `Message.Assistant` 时，会立即将该字符串解析为 JSON。
 * 如果模型返回了未转义引号等不合法内容，转换过程会直接抛出 `JsonDecodingException`，
 *
 */
internal fun repairOpenAIToolCallResponse(
    response: OpenAIChatCompletionResponse,
    onRepair: (toolName: String) -> Unit = {},
): OpenAIChatCompletionResponse {
    var changed = false
    val choices = response.choices.map { choice ->
        val message = choice.message.repairToolCallArguments { toolName ->
            changed = true
            onRepair(toolName)
        }
        OpenAIChoice(
            finishReason = choice.finishReason,
            index = choice.index,
            logprobs = choice.logprobs,
            message = message,
        )
    }
    if (!changed) return response

    return OpenAIChatCompletionResponse(
        choices = choices,
        created = response.created,
        id = response.id,
        model = response.model,
        serviceTier = response.serviceTier,
        systemFingerprint = response.systemFingerprint,
        objectType = response.objectType,
        usage = response.usage,
    )
}

private fun OpenAIMessage.repairToolCallArguments(
    onRepair: (toolName: String) -> Unit,
): OpenAIMessage {
    // 只处理 Assistant 的 function.arguments，其他 OpenAI 消息保持原样。
    if (this !is OpenAIMessage.Assistant) return this
    val calls = toolCalls ?: return this

    return OpenAIMessage.Assistant(
        content = content,
        reasoningContent = reasoningContent,
        audio = audio,
        name = name,
        refusal = refusal,
        toolCalls = calls.map { call ->
            val repaired = ToolCallArgumentNormalizer.repairResponse(call.function.arguments)
            if (repaired != call.function.arguments) onRepair(call.function.name)
            OpenAIToolCall(
                id = call.id,
                function = OpenAIFunction(
                    name = call.function.name,
                    arguments = repaired,
                ),
            )
        },
        annotations = annotations,
    )
}
