package uesugi.core.component.llm.client.openai

import ai.koog.prompt.executor.clients.openai.base.models.OpenAIFunction
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolCall
import uesugi.core.component.llm.toolcall.ToolCallArgumentNormalizer

/**
 * 撤销 Koog 在构造 OpenAI 请求时对 Tool Call arguments 额外执行的一次字符串序列化。
 *
 * Koog 1.0.0 会对已经是 JSON 字符串的 `MessagePart.Tool.Call.args` 调用
 * `Json.encodeToString`，导致 Provider 收到字符串而不是参数对象。本函数在最终请求序列化前
 * 将这层编码解开，使 OpenAI wire JSON 中的 `function.arguments` 内容重新成为 JSON 对象文本。
 */
internal fun normalizeOpenAIToolCallRequest(
    messages: List<OpenAIMessage>,
): List<OpenAIMessage> = messages.map { message ->
    if (message !is OpenAIMessage.Assistant) return@map message
    val calls = message.toolCalls ?: return@map message

    OpenAIMessage.Assistant(
        content = message.content,
        reasoningContent = message.reasoningContent,
        audio = message.audio,
        name = message.name,
        refusal = message.refusal,
        toolCalls = calls.map { call ->
            OpenAIToolCall(
                id = call.id,
                function = OpenAIFunction(
                    name = call.function.name,
                    arguments = ToolCallArgumentNormalizer.normalizeRequest(call.function.arguments),
                ),
            )
        },
        annotations = message.annotations,
    )
}
