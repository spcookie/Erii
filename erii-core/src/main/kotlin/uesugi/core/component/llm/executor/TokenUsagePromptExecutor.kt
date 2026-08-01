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
import kotlinx.coroutines.flow.onEach
import uesugi.core.component.usage.TokenUsageRepository

class TokenUsagePromptExecutor(
    private val delegate: PromptExecutor,
    private val repository: TokenUsageRepository
) : PromptExecutor() {

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Message.Assistant {
        val response = delegate.execute(prompt, model, tools)
        repository.record(prompt, model, response)
        return response
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> {
        return delegate.executeStreaming(prompt, model, tools)
            .onEach { frame ->
                if (frame is StreamFrame.End) {
                    repository.record(
                        prompt = prompt,
                        model = model,
                        response = Message.Assistant(
                            content = "",
                            metaInfo = frame.metaInfo,
                            finishReason = frame.finishReason
                        )
                    )
                }
            }
    }

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): LLMChoice {
        val response = delegate.executeMultipleChoices(prompt, model, tools)
        recordChoices(prompt, model, response)
        return response
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult {
        return delegate.moderate(prompt, model)
    }

    override fun close() {
        delegate.close()
    }

    private fun recordChoices(prompt: Prompt, model: LLModel, response: LLMChoice) {
        response.forEach {
            repository.record(prompt, model, it)
        }
    }
}
