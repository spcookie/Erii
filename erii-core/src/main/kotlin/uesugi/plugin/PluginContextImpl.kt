package uesugi.plugin

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import io.ktor.client.*
import kotlinx.coroutines.*
import uesugi.common.BotManage
import uesugi.common.EventBus
import uesugi.common.IntegrationEvent
import uesugi.common.toolkit.logger
import uesugi.core.component.usage.UsageContext
import uesugi.core.route.MetaToolSetRegister
import uesugi.core.route.RouteCalledEvent
import uesugi.spi.*
import uesugi.spi.annotation.withPluginContext
import kotlin.time.Duration.Companion.milliseconds

class PluginContextImpl(
    private val pluginId: String,
    private val extensionName: String,
    override val defined: PluginDef,
    override val mem: Mem,
    override val kv: Kv,
    override val blob: Blob,
    override val vector: Vector,
    override val config: PluginConfig,
    override val scheduler: Scheduler,
    llm: PromptExecutor,
    override val http: HttpClient,
    override val server: Server,
    val httpProxy: HttpClient
) : PluginContextBootstrap {

    override val database: Database = PluginDatabaseImpl(defined.name)

    companion object {
        val log = logger()
    }

    private val supervisorJob = SupervisorJob()
    private val scope =
        CoroutineScope(Dispatchers.IO + supervisorJob + CoroutineName("PluginContext-${defined.name}") + CoroutineExceptionHandler { _, exception ->
            log.error("Plugin context detected error: {}", exception.message, exception)
        })

    private lateinit var job: Job
    private lateinit var eventJob: Job
    private var syncEventHandler: ((IntegrationEvent) -> Unit)? = null

    private val eventHandlers = mutableListOf<suspend (IntegrationEvent) -> Unit>()

    override val llm: PromptExecutor = if (defined.name.startsWith("builtin_", ignoreCase = true)) {
        llm
    } else {
        PluginPromptExecutor(defined.name, llm)
    }

    private val handlers = mutableListOf<Handler>()
    private val toolsets = mutableListOf<MetaToolSetCreator>()

    override val HttpClient.proxy: HttpClient
        get() = httpProxy

    override fun chain(handler: Handler) {
        handlers += handler
    }

    override fun tool(toolset: PluginContext.() -> MetaToolSetCreator) {
        toolsets += toolset(this)
    }

    override fun onEvent(handler: suspend (IntegrationEvent) -> Unit) {
        eventHandlers += handler
    }

    override fun registerCommandExample(example: String, description: String) {
        PluginCommandExampleRegistry.register(pluginId, extensionName, example, description)
    }

    override fun start() {
        job = EventBus.subscribeAsync<RouteCalledEvent>(scope) { event ->
            scope.launch {
                UsageContext.withUsage(event.botId, event.groupId) {
                    for (rk in defined.routeKeys) {
                        if (event hit rk) {
                            val meta = MetaImpl(
                                botId = event.botId,
                                groupId = event.groupId,
                                roledBot = BotManage.getBot(event.botId),
                                input = event.input,
                                senderId = event.senderId,
                                echo = event.echo
                            )
                            for (handler in handlers) {
                                withContext(meta.asContextElement()) {
                                    handler(meta)
                                }
                            }
                            break
                        }
                    }
                }
            }
        }

        eventJob = EventBus.subscribeAsync<IntegrationEvent>(scope) { event ->
            scope.launch {
                withPluginContext(this@PluginContextImpl) {
                    for (handler in eventHandlers.toList()) {
                        handler(event)
                    }
                }
            }
        }
        syncEventHandler = EventBus.subscribeSync<IntegrationEvent> { event ->
            runBlocking {
                withPluginContext(this@PluginContextImpl) {
                    for (handler in eventHandlers.toList()) {
                        handler(event)
                    }
                }
            }
        }

        for (toolset in toolsets) {
            MetaToolSetRegister.addToolSet(defined.name, toolset)
        }
    }

    override fun close() {
        PluginCommandExampleRegistry.removeExtension(pluginId, extensionName)
        if (this::job.isInitialized) {
            EventBus.unsubscribeAsync(job)
        }
        if (this::eventJob.isInitialized) {
            EventBus.unsubscribeAsync(eventJob)
        }
        syncEventHandler?.let {
            EventBus.unsubscribeSync(it)
            syncEventHandler = null
        }
        supervisorJob.cancel(CancellationException("Plugin context ${defined.name} closed"))
        runBlocking {
            withTimeoutOrNull(3_000.milliseconds) {
                supervisorJob.children.forEach { it.join() }
            }
        }
    }

}

private class PluginPromptExecutor(
    private val pluginName: String,
    private val delegate: PromptExecutor
) : PromptExecutor() {

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Message.Assistant {
        return delegate.execute(markPluginPrompt(prompt), model, tools)
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): kotlinx.coroutines.flow.Flow<StreamFrame> {
        return delegate.executeStreaming(markPluginPrompt(prompt), model, tools)
    }

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): LLMChoice {
        return delegate.executeMultipleChoices(markPluginPrompt(prompt), model, tools)
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult {
        return delegate.moderate(markPluginPrompt(prompt), model)
    }

    override fun close() {
        delegate.close()
    }

    private fun markPluginPrompt(prompt: Prompt): Prompt {
        return prompt.copy(id = "__plugin_${pluginName.sanitizePromptId()}|${prompt.id}__")
    }

    private fun String.sanitizePromptId(): String =
        lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_').ifBlank { "unknown" }
}
