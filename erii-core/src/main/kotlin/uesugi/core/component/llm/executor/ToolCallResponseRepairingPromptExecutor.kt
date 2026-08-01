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
import kotlinx.coroutines.flow.map
import uesugi.common.toolkit.logger
import uesugi.core.component.llm.toolcall.ToolCallArgumentNormalizer
import uesugi.core.component.llm.toolcall.mapToolCallArguments

/**
 * Provider 路由之后的通用 Tool Call 响应修复器。
 *
 * 统一处理普通响应、多候选响应以及已经聚合完成的流式 Tool Call，
 */
internal class ToolCallResponseRepairingPromptExecutor(
    private val delegate: PromptExecutor,
) : PromptExecutor() {

    private val log = logger()

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant = delegate.execute(prompt, model, tools).repairToolCallArguments()

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = delegate.executeStreaming(prompt, model, tools).map(::repairToolCallFrame)

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): LLMChoice = delegate.executeMultipleChoices(prompt, model, tools).map { response ->
        response.repairToolCallArguments()
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        delegate.moderate(prompt, model)

    override fun close() {
        delegate.close()
    }

    private fun Message.Assistant.repairToolCallArguments(): Message.Assistant =
        mapToolCallArguments(::repairArguments)

    private fun repairToolCallFrame(frame: StreamFrame): StreamFrame {
        // Delta 仍是 JSON 片段，只有参数聚合完成后才能进行完整 JSON 修复。
        if (frame !is StreamFrame.ToolCallComplete) return frame
        return frame.copy(content = repairArguments(frame.name, frame.content))
    }

    private fun repairArguments(toolName: String, arguments: String): String {
        val repaired = ToolCallArgumentNormalizer.repairResponse(arguments)
        if (repaired != arguments) {
            log.warn("Repaired malformed tool arguments returned by LLM: tool={}", toolName)
        }
        return repaired
    }
}
