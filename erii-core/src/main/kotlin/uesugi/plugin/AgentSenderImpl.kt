package uesugi.plugin

import ai.koog.serialization.JSONElement
import com.google.auto.service.AutoService
import kotlinx.coroutines.runBlocking
import uesugi.common.EventBus
import uesugi.common.event.*
import uesugi.common.event.agent.AgentCallCompleteEvent
import uesugi.common.event.agent.AgentCallFallbackEvent
import uesugi.common.event.agent.AgentCallRejectEvent
import uesugi.common.event.agent.AgentCallStartEvent
import uesugi.common.event.agent.AgentDispatchEvent
import uesugi.common.event.agent.AgentRunCompleteEvent
import uesugi.common.event.agent.AgentRunStartEvent
import uesugi.common.event.agent.AgentToolCallCompleteEvent
import uesugi.common.event.agent.AgentToolCallEvent
import uesugi.common.event.agent.AgentToolCallStartEvent
import uesugi.spi.*

@AutoService(AgentSender::class)
class AgentSenderImpl : AgentSender {
    override fun sendAgent(
        botId: String,
        groupId: String,
        input: String,
        dsl: SendAgentStateDsl?
    ) = sendAgent(botId, groupId, input, EmptyConfig, dsl)

    override fun sendAgent(
        botId: String,
        groupId: String,
        input: String,
        config: SendAgentConfig,
        dsl: SendAgentStateDsl?
    ) {
        val holder = mutableMapOf<String, Any>()
        val fn = dsl?.let {
            val scope = SendAgentStateBuilder(holder).dsl()
            @Suppress("UNCHECKED_CAST")
            object : SendAgentState {
                override val scope = scope

                override suspend fun callToolStart(toolName: String, toolArgs: JSONElement) {
                    (holder["callToolStart"] as? suspend (String, JSONElement) -> Unit)?.invoke(toolName, toolArgs)
                }

                override suspend fun callToolCompletion(
                    toolName: String,
                    toolArgs: JSONElement,
                    toolResult: JSONElement?,
                    toolError: String?
                ) {
                    (holder["callToolCompletion"] as? suspend (
                        String,
                        JSONElement,
                        JSONElement?,
                        String?
                    ) -> Unit)?.invoke(
                        toolName,
                        toolArgs,
                        toolResult,
                        toolError
                    )
                }

                override suspend fun runStart() {
                    (holder["runStart"] as? suspend () -> Unit)?.invoke()
                }

                override suspend fun runCompletion(error: Throwable?) {
                    (holder["runCompletion"] as? suspend (Throwable?) -> Unit)?.invoke(error)
                }

                override suspend fun callReject() {
                    (holder["callReject"] as? suspend () -> Unit)?.invoke()
                }

                override suspend fun callFallback() {
                    (holder["callFallback"] as? suspend () -> Unit)?.invoke()
                }

                override suspend fun callStart() {
                    (holder["callStart"] as? suspend () -> Unit)?.invoke()
                }

                override suspend fun callCompletion(error: Throwable?) {
                    (holder["callCompletion"] as? suspend (Throwable?) -> Unit)?.invoke(error)
                }
            }
        }
        val conf = SendAgentConf(
            chatVision = config[ChatVision]?.let { it == ChatVision.ENABLE } ?: false,
            webSearch = config[WebSearch]?.let { it == WebSearch.ENABLE } ?: false,
            toolSetBuilder = config[ToolSetBuilder]?.value,
            feature = config[Feature]?.value ?: PSFeature.NONE,
        )
        sendAgent(botId, groupId, input, conf, fn)
    }


    private fun sendAgent(
        botId: String,
        groupId: String,
        input: String,
        conf: SendAgentConf = SendAgentConf(),
        state: SendAgentState? = null
    ) {
        val (chatVision, webSearch, toolSets, flag, echo) = conf

        if (state != null) {
            val job = EventBus.subscribeAsync<AgentToolCallEvent>(state.scope) { event ->
                val toolCallEvent =
                    event.takeIf { event.botId == botId && event.groupId == groupId && event.echo == echo }
                if (toolCallEvent != null) {
                    when (toolCallEvent) {
                        is AgentToolCallStartEvent -> state.callToolStart(
                            toolCallEvent.toolName,
                            toolCallEvent.toolArgs
                        )

                        is AgentToolCallCompleteEvent -> state.callToolCompletion(
                            toolCallEvent.toolName,
                            toolCallEvent.toolArgs,
                            toolCallEvent.toolResult,
                            toolCallEvent.toolError
                        )
                    }
                }
            }

            EventBus.subscribeOnceSync<AgentCallStartEvent> { event ->
                val e = event.takeIf { event.botId == botId && event.groupId == groupId && event.echo == echo }
                if (e != null) {
                    runBlocking(state.scope.coroutineContext) {
                        state.callStart()
                    }
                }
            }

            val runStart = EventBus.subscribeSync<AgentRunStartEvent> { event ->
                val e = event.takeIf { event.botId == botId && event.groupId == groupId && event.echo == echo }
                if (e != null) {
                    runBlocking(state.scope.coroutineContext) {
                        state.runStart()
                    }
                }
            }

            val runCompletion = EventBus.subscribeSync<AgentRunCompleteEvent> { event ->
                val e = event.takeIf { event.botId == botId && event.groupId == groupId && event.echo == echo }
                if (e != null) {
                    runBlocking(state.scope.coroutineContext) {
                        state.runCompletion(event.throwable)
                    }
                }
            }

            fun AgentDispatchEvent.call(block: () -> Unit) {
                val e = this.takeIf { this.botId == botId && this.groupId == groupId && this.echo == echo }
                if (e != null) {
                    runCatching { block() }
                    EventBus.unsubscribeAsync(job)
                    EventBus.unsubscribeSync(runStart)
                    EventBus.unsubscribeSync(runCompletion)
                }
            }

            EventBus.subscribeOnceSync<AgentCallRejectEvent> { event ->
                event.call { runBlocking(state.scope.coroutineContext) { state.callReject() } }
            }

            EventBus.subscribeOnceSync<AgentCallFallbackEvent> { event ->
                event.call { runBlocking(state.scope.coroutineContext) { state.callFallback() } }
            }

            EventBus.subscribeOnceSync<AgentCallCompleteEvent> { event ->
                event.call { runBlocking(state.scope.coroutineContext) { state.callCompletion(event.throwable) } }
            }
        }

        EventBus.postAsync(
            ProactiveSpeakEvent(
                botId = botId,
                _groupId = groupId,
                interruptionMode = InterruptionMode.Interrupt,
                input = input,
                chatVision = chatVision,
                webSearch = webSearch,
                toolSetBuilder = toolSets,
                feature = flag,
                echo = echo
            )
        )

    }

}
