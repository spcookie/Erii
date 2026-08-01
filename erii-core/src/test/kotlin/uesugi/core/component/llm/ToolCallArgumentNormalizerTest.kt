package uesugi.core.component.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import uesugi.core.component.llm.toolcall.ToolCallArgumentNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolCallArgumentNormalizerTest {

    @Test
    fun `unwraps double encoded request arguments`() {
        val arguments = """{"texts":["hello"]}"""

        assertEquals(
            arguments,
            ToolCallArgumentNormalizer.normalizeRequest(Json.encodeToString(arguments)),
        )
    }

    @Test
    fun `repairs unescaped quotes in response arguments`() {
        val malformed = """{"texts": ["两条都是问"你能听见我说话吗"——测试语音的😂"]}"""

        val repaired = ToolCallArgumentNormalizer.repairResponse(malformed)
        val text = Json.parseToJsonElement(repaired)
            .jsonObject
            .getValue("texts")
            .jsonArray
            .single()
            .jsonPrimitive
            .content

        assertEquals("两条都是问\"你能听见我说话吗\"——测试语音的😂", text)
    }

    @Test
    fun `leaves unrecoverable response arguments unchanged`() {
        val malformed = """{"texts": ["unfinished""""

        assertEquals(malformed, ToolCallArgumentNormalizer.repairResponse(malformed))
    }
}
