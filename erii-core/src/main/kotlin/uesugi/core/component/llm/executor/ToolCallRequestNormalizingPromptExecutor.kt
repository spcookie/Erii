package uesugi.core.component.llm.executor

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import uesugi.core.component.llm.toolcall.ToolCallArgumentNormalizer
import uesugi.core.component.llm.toolcall.mapToolCallArguments

/** 在进入 Provider 路由前，解开历史 Tool Call 参数的重复 JSON 序列化。 */
internal class ToolCallRequestNormalizingPromptExecutor(
    private val delegate: PromptExecutor,
) : PromptExecutor() {

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant = delegate.execute(prompt.normalizeToolCallArguments(), model, tools)

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = delegate.executeStreaming(prompt.normalizeToolCallArguments(), model, tools)

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): LLMChoice = delegate.executeMultipleChoices(prompt.normalizeToolCallArguments(), model, tools)

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        delegate.moderate(prompt, model)

    override fun close() {
        delegate.close()
    }
}

private fun Prompt.normalizeToolCallArguments(): Prompt = withMessages { messages ->
    messages.map { message ->
        if (message is Message.Assistant) {
            message.mapToolCallArguments { _, arguments ->
                ToolCallArgumentNormalizer.normalizeRequest(arguments)
            }
        } else {
            message
        }
    }
}
