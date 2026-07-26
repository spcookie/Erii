package uesugi.plugin

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import uesugi.common.EventBus
import uesugi.common.event.CliPluginEvent
import uesugi.common.event.CliPluginReplyEvent
import uesugi.routing.dispatchCliPluginEvent
import uesugi.spi.Blob
import uesugi.spi.Kv
import uesugi.spi.Mem
import uesugi.spi.PluginConfig
import uesugi.spi.PluginDef
import uesugi.spi.RouteKey
import uesugi.spi.Scheduler
import uesugi.spi.Server
import uesugi.spi.Vector
import uesugi.spi.annotation.useConfig
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class PluginContextEventTest {
    @Test
    fun `event handlers run with their plugin context`() {
        val config = proxy<PluginConfig>()
        val http = HttpClient()
        val httpProxy = HttpClient()
        val context = PluginContextImpl(
            pluginId = "test-plugin",
            extensionName = "TestExtension",
            defined = object : PluginDef {
                override val name = "builtin_test_event"
                override val routeKeys: List<RouteKey> = emptyList()
            },
            mem = proxy<Mem>(),
            kv = proxy<Kv>(),
            blob = proxy<Blob>(),
            vector = proxy<Vector>(),
            config = config,
            scheduler = proxy<Scheduler>(),
            llm = UnusedPromptExecutor(),
            http = http,
            server = proxy<Server>(),
            httpProxy = httpProxy,
        )
        context.onEvent { event ->
            if (event is CliPluginEvent) {
                assertSame(config, useConfig())
                EventBus.postSync(CliPluginReplyEvent(event.echo, "context available"))
            }
        }
        context.start()
        try {
            val result = runBlocking { dispatchCliPluginEvent("check context", timeoutMillis = 1_000) }

            assertNull(result.failure)
            assertEquals("context available", result.reply)
        } finally {
            context.close()
            http.close()
            httpProxy.close()
        }
    }
}

private inline fun <reified T : Any> proxy(): T = Proxy.newProxyInstance(
    T::class.java.classLoader,
    arrayOf(T::class.java),
) { instance, method, args ->
    when (method.name) {
        "equals" -> instance === args?.firstOrNull()
        "hashCode" -> System.identityHashCode(instance)
        "toString" -> "TestProxy<${T::class.simpleName}>"
        else -> null
    }
} as T

private class UnusedPromptExecutor : PromptExecutor() {
    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant = error("not used")

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = emptyFlow()

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("not used")

    override fun close() = Unit
}
