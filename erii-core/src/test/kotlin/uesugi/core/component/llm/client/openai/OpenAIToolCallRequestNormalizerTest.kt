package uesugi.core.component.llm.client.openai

import ai.koog.prompt.executor.clients.openai.base.models.OpenAIFunction
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolCall
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenAIToolCallRequestNormalizerTest {

    @Test
    fun `unwraps arguments encoded by Koog before serializing OpenAI request`() {
        val arguments = """{"texts":["hello"]}"""
        val message = OpenAIMessage.Assistant(
            toolCalls = listOf(
                OpenAIToolCall(
                    id = "call-id",
                    function = OpenAIFunction(
                        name = "sendText",
                        arguments = Json.encodeToString(arguments),
                    ),
                ),
            ),
        )

        val normalized = normalizeOpenAIToolCallRequest(listOf(message))
        val call = (normalized.single() as OpenAIMessage.Assistant).toolCalls!!.single()

        assertEquals(arguments, call.function.arguments)
    }

    @Test
    fun `keeps valid arguments unchanged`() {
        val arguments = """{"texts":["hello"]}"""
        val message = OpenAIMessage.Assistant(
            toolCalls = listOf(
                OpenAIToolCall(
                    id = "call-id",
                    function = OpenAIFunction(
                        name = "sendText",
                        arguments = arguments,
                    ),
                ),
            ),
        )

        val normalized = normalizeOpenAIToolCallRequest(listOf(message))
        val call = (normalized.single() as OpenAIMessage.Assistant).toolCalls!!.single()

        assertEquals(arguments, call.function.arguments)
    }
}
