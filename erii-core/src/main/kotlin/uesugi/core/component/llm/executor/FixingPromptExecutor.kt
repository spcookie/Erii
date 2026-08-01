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

/**
 * Wraps a [PromptExecutor] to ensure structure-fixing prompts receive
 * generous maxTokens (default 16384), preventing truncated fixes
 * when the upstream StructureFixingParser omits this parameter.
 */
class FixingPromptExecutor(
    private val delegate: PromptExecutor,
    private val fixingMaxTokens: Int = 16384
) : PromptExecutor() {

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Message.Assistant {
        return delegate.execute(ensureTokens(prompt), model, tools)
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> {
        return delegate.executeStreaming(ensureTokens(prompt), model, tools)
    }

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): LLMChoice {
        return delegate.executeMultipleChoices(ensureTokens(prompt), model, tools)
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult {
        return delegate.moderate(prompt, model)
    }

    override fun close() {
        delegate.close()
    }

    private fun ensureTokens(prompt: Prompt): Prompt {
        if (prompt.id != "structure-fixing") return prompt
        return prompt.copy(params = prompt.params.copy(maxTokens = fixingMaxTokens))
    }
}
