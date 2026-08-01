package uesugi.core.component.llm

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uesugi.core.component.llm.executor.TokenUsagePromptExecutor
import uesugi.core.component.usage.TokenUsageRepository
import uesugi.core.component.usage.TokenUsageTable
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class TokenUsagePromptExecutorTest {
    @OptIn(ExperimentalTime::class)
    @Test
    fun `streaming response records usage from end frame`() = runBlocking {
        withUsageDatabase {
            val prompt = Prompt(emptyList(), "__bot_chat__")
            val model = LLModel(LLMProvider("test-provider", "Test Provider"), "test-model")
            val executor = TokenUsagePromptExecutor(
                delegate = StreamingDelegate(
                    StreamFrame.TextDelta("hi"),
                    StreamFrame.End(
                        finishReason = "stop",
                        metaInfo = ResponseMetaInfo(
                            timestamp = Clock.System.now(),
                            totalTokensCount = 15,
                            inputTokensCount = 10,
                            outputTokensCount = 5,
                            modelId = "stream-model"
                        )
                    )
                ),
                repository = TokenUsageRepository()
            )

            executor.executeStreaming(prompt, model, emptyList()).toList()

            val summary = TokenUsageRepository().summary()
            assertEquals(10, summary.totalCacheMissInput)
            assertEquals(5, summary.totalOutput)
            assertEquals(15, summary.dailySeries.single().tokens)
        }
    }

    private suspend fun withUsageDatabase(block: suspend () -> Unit) {
        val database = Database.connect(
            url = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        TransactionManager.defaultDatabase = database
        transaction(database) {
            SchemaUtils.create(TokenUsageTable)
        }
        block()
    }

    private class StreamingDelegate(
        private vararg val frames: StreamFrame
    ) : PromptExecutor() {
        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Message.Assistant {
            error("not used")
        }

        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Flow<StreamFrame> = flowOf(*frames)

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult {
            error("not used")
        }

        override fun close() {
        }
    }
}
