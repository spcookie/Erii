package uesugi.core.component.llm

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import uesugi.core.component.llm.executor.ToolCallRequestNormalizingPromptExecutor
import uesugi.core.component.llm.executor.ToolCallResponseRepairingPromptExecutor
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolCallPromptExecutorDecoratorTest {

    private val model = LLModel(LLMProvider("test", "Test"), "test-model")

    @Test
    fun `normalizes request arguments before provider routing`() = runBlocking {
        val arguments = """{"texts":["hello"]}"""
        val delegate = CapturingPromptExecutor(emptyAssistant())
        val executor = ToolCallRequestNormalizingPromptExecutor(delegate)
        val prompt = Prompt(
            messages = listOf(toolCallAssistant(Json.encodeToString(arguments))),
            id = "request-normalizer-test",
        )

        executor.execute(prompt, model)

        val call = delegate.lastPrompt!!.messages.single().parts.single() as MessagePart.Tool.Call
        assertEquals(arguments, call.args)
    }

    @Test
    fun `repairs response arguments after provider routing`() = runBlocking {
        val malformed = """{"texts": ["prefix "quoted" suffix"]}"""
        val executor = ToolCallResponseRepairingPromptExecutor(
            CapturingPromptExecutor(toolCallAssistant(malformed)),
        )

        val response = executor.execute(Prompt.Empty, model)
        val call = response.parts.single() as MessagePart.Tool.Call

        assertEquals("""{"texts": ["prefix \"quoted\" suffix"]}""", call.args)
    }

    private fun toolCallAssistant(arguments: String): Message.Assistant = Message.Assistant(
        parts = listOf(MessagePart.Tool.Call(id = "call-id", tool = "sendText", args = arguments)),
        metaInfo = ResponseMetaInfo.Empty,
    )

    private fun emptyAssistant(): Message.Assistant = Message.Assistant(
        parts = emptyList(),
        metaInfo = ResponseMetaInfo.Empty,
    )

    private class CapturingPromptExecutor(
        private val response: Message.Assistant,
    ) : PromptExecutor() {

        var lastPrompt: Prompt? = null

        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): Message.Assistant {
            lastPrompt = prompt
            return response
        }

        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ) = error("Not needed by this test")

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            error("Not needed by this test")

        override fun close() = Unit
    }
}
