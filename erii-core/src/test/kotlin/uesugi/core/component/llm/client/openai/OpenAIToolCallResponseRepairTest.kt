package uesugi.core.component.llm.client.openai

import ai.koog.prompt.executor.clients.openai.base.models.OpenAIFunction
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolCall
import ai.koog.prompt.executor.clients.openai.models.OpenAIChatCompletionResponse
import ai.koog.prompt.executor.clients.openai.models.OpenAIChoice
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenAIToolCallResponseRepairTest {

    @Test
    fun `repairs tool arguments before Koog converts the provider response`() {
        val malformed = """{"texts": ["两条都是问"你能听见我说话吗"——测试语音的😂"]}"""
        val response = OpenAIChatCompletionResponse(
            choices = listOf(
                OpenAIChoice(
                    finishReason = "tool_calls",
                    index = 0,
                    message = OpenAIMessage.Assistant(
                        toolCalls = listOf(
                            OpenAIToolCall(
                                id = "call-id",
                                function = OpenAIFunction("sendText", malformed),
                            ),
                        ),
                    ),
                ),
            ),
            created = 0,
            id = "response-id",
            model = "model-id",
            objectType = "chat.completion",
        )

        val repaired = repairOpenAIToolCallResponse(response)
        val arguments = (repaired.choices.single().message as OpenAIMessage.Assistant)
            .toolCalls!!
            .single()
            .function
            .arguments
        val text = Json.parseToJsonElement(arguments)
            .jsonObject
            .getValue("texts")
            .jsonArray
            .single()
            .jsonPrimitive
            .content

        assertEquals("两条都是问\"你能听见我说话吗\"——测试语音的😂", text)
    }
}
