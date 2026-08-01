package uesugi.core.component.llm.executor

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import uesugi.common.LLMModelChoice

/**
 * Wraps a [PromptExecutor] to inject default params per model tier
 * via [LLMParams.additionalProperties].
 *
 * Default params are keyed by tier name ("lite", "flash", "pro").
 */
class DefaultParamPromptExecutor(
    private val delegate: PromptExecutor,
    private val defaultParams: Map<String, Map<String, JsonElement>>
) : PromptExecutor() {

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Message.Assistant {
        return delegate.execute(applyDefaults(prompt, model), model, tools)
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> {
        return delegate.executeStreaming(applyDefaults(prompt, model), model, tools)
    }

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): LLMChoice {
        return delegate.executeMultipleChoices(applyDefaults(prompt, model), model, tools)
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult {
        return delegate.moderate(prompt, model)
    }

    override fun close() {
        delegate.close()
    }

    private fun applyDefaults(prompt: Prompt, model: LLModel): Prompt {
        val tier = resolveTier(model) ?: return prompt
        val defaults = defaultParams[tier] ?: return prompt
        return prompt.copy(
            params = prompt.params.default(LLMParams(additionalProperties = defaults))
        )
    }

    private fun resolveTier(model: LLModel): String? {
        return when (model) {
            LLMModelChoice.Lite -> "lite"
            LLMModelChoice.Flash -> "flash"
            LLMModelChoice.Pro -> "pro"
            else -> null
        }
    }
}
