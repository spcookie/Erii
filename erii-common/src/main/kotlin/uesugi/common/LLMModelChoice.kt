package uesugi.common

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import uesugi.common.toolkit.ConfigHolder
import uesugi.common.toolkit.logger

object LLMModelChoice {

    private val log = logger()

    private fun capabilityKey(cap: LLMCapability): String = when (cap) {
        LLMCapability.Completion -> "completion"
        LLMCapability.PromptCaching -> "prompt-caching"
        LLMCapability.Temperature -> "temperature"
        LLMCapability.Tools -> "tools"
        LLMCapability.ToolChoice -> "tool-choice"
        LLMCapability.MultipleChoices -> "multiple-choices"
        LLMCapability.Thinking -> "thinking"
        LLMCapability.Vision.Image -> "vision-image"
        LLMCapability.Audio -> "audio"
        else -> ""
    }

    private fun filterCapabilities(tier: String, capabilities: List<LLMCapability>): List<LLMCapability> =
        capabilities.filter { cap ->
            val key = capabilityKey(cap)
            key.isEmpty() || ConfigHolder.isLlmCapabilityEnabled(tier, key)
        }

    private val choice by lazy {
        val choice = ConfigHolder.getChoiceProvider()
        log.info("apply llm provider: $choice")
        choice
    }

    private fun resolveModelId(providerModels: Map<String, String>, tier: String): String {
        val all = providerModels["all"]
        return if (!all.isNullOrBlank()) all else providerModels[tier] ?: ""
    }

    private fun openaiModel(tier: String): LLModel {
        val models = ConfigHolder.getLlmOpenAIModels()
        val modelId = resolveModelId(models, tier)
        return LLModel(
            provider = LLMProvider.OpenAI,
            id = modelId,
            capabilities = filterCapabilities(
                tier,
                listOf(
                    LLMCapability.Completion,
                    LLMCapability.PromptCaching,
                    LLMCapability.Temperature,
                    LLMCapability.Thinking,
                    LLMCapability.Tools,
                    LLMCapability.ToolChoice,
                    LLMCapability.MultipleChoices,
                    LLMCapability.Vision.Image,
                    LLMCapability.Audio,
                    LLMCapability.OpenAIEndpoint.Completions,
                )
            )
        )
    }

    private fun anthropicModel(tier: String): LLModel {
        val models = ConfigHolder.getLlmAnthropicModels()
        val modelId = resolveModelId(models, tier)
        return LLModel(
            provider = LLMProvider.Anthropic,
            id = modelId,
            capabilities = filterCapabilities(
                tier,
                listOf(
                    LLMCapability.Completion,
                    LLMCapability.PromptCaching,
                    LLMCapability.Temperature,
                    LLMCapability.Tools,
                    LLMCapability.ToolChoice,
                    LLMCapability.Thinking,
                    LLMCapability.MultipleChoices,
                    LLMCapability.Vision.Image,
                    LLMCapability.Audio,
                )
            ),
        )
    }

    val Lite by lazy {
        when (choice) {
            "OPENAI" -> openaiModel("lite")
            "ANTHROPIC" -> anthropicModel("lite")
            else -> throw RuntimeException("Unknown choice provider: $choice")
        }
    }

    val Flash by lazy {
        when (choice) {
            "OPENAI" -> openaiModel("flash")
            "ANTHROPIC" -> anthropicModel("flash")
            else -> throw RuntimeException("Unknown choice provider: $choice")
        }
    }

    val Pro by lazy {
        when (choice) {
            "OPENAI" -> openaiModel("pro")
            "ANTHROPIC" -> anthropicModel("pro")
            else -> throw RuntimeException("Unknown choice provider: $choice")
        }
    }

}
