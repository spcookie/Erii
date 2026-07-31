package uesugi.core.agent

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.markdown.markdown
import ai.koog.prompt.params.LLMParams
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import uesugi.common.BotManage
import uesugi.common.EventBus
import uesugi.common.LLMModelChoice
import uesugi.common.data.HistoryRecord
import uesugi.common.event.ChatUrgentEvent
import uesugi.common.event.InterruptionMode
import uesugi.common.event.ProactiveSpeakEvent
import uesugi.common.toolkit.ref
import uesugi.core.message.history.HistorySavedEvent
import uesugi.core.route.MetaToolSetRegister
import uesugi.plugin.MetaImpl
import kotlin.coroutines.CoroutineContext
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class MessageAwaiter(val context: Context) : AutoCloseable, CoroutineScope {

    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val historyChannel = Channel<ProactiveSpeakEvent>()
    private lateinit var historyJob: Job

    private val relevanceChannel = Channel<RelevanceType>(Channel.CONFLATED)

    private val chatChannel = Channel<ProactiveSpeakEvent>()
    private lateinit var chatUrgentJob: Job

    private val continueChannel = Channel<ProactiveSpeakEvent>()

    private enum class RelevanceType {
        Message, Continue
    }

    val onReceiveMessageContinue
        get() = historyChannel.onReceiveCatching

    val onChatUrgentContinue
        get() = chatChannel.onReceiveCatching

    fun fare() {
        launch {
            relevanceChannel.receiveAsFlow()
                .collect { type ->
                    try {
                        if (isRelevanceContinue(context.histories(), context.currentBotId)) {
                            when (type) {
                                RelevanceType.Message -> {
                                    historyChannel.trySend(speak(context.currentBotId, context.groupId))
                                }

                                RelevanceType.Continue -> {
                                    continueChannel.trySend(speak(context.currentBotId, context.groupId))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        log.warn(e) { "Failed to determine relevance continue" }
                    }
                }
        }

        historyJob = EventBus.subscribeAsync<HistorySavedEvent>(this) { event ->
            val record = event.historyRecord
            if (record.isMessageFor(context.currentBotId, context.groupId)) {
                if (!event.isAtBot) {
                    relevanceChannel.send(RelevanceType.Message)
                }
            }
        }

        chatUrgentJob = EventBus.subscribeAsync<ChatUrgentEvent>(this) { event ->
            val speak = event.urgent
            if (speak.groupId == context.groupId && speak.botId == context.currentBotId) {
                chatChannel.send(speak(context.currentBotId, context.groupId, speak.senderId, speak.input))
            }
        }

    }

    @OptIn(ExperimentalUuidApi::class)
    private fun speak(
        botId: String,
        groupId: String,
        senderId: String? = null,
        input: String? = null
    ): ProactiveSpeakEvent {
        val echo = Uuid.random().toHexString()
        val meta = MetaImpl(
            botId = botId,
            groupId = groupId,
            senderId = senderId,
            roledBot = BotManage.getBot(botId),
            input = input,
            echo = echo
        )
        val metaToolSets = MetaToolSetRegister.getToolSetsForBot(botId)
            .map { createToolSet -> createToolSet(meta) }
        return ProactiveSpeakEvent(
            botId = botId,
            _groupId = groupId,
            senderId = senderId,
            chatVision = true,
            webSearch = true,
            interruptionMode = InterruptionMode.Interrupt,
            input = input,
            echo = echo,
            toolSetBuilder = { metaToolSets }
        )
    }

    override fun close() {
        if (::historyJob.isInitialized) {
            historyJob.cancel()
        }
        if (::chatUrgentJob.isInitialized) {
            chatUrgentJob.cancel()
        }

        cancel()

        relevanceChannel.close()
        continueChannel.close()
        historyChannel.close()
        chatChannel.close()
    }

    override val coroutineContext: CoroutineContext
        get() = SupervisorJob() +
                CoroutineName("message-awaiter-${context.currentBotId}-${context.groupId}") +
                Dispatchers.Default +
                CoroutineExceptionHandler { _, error ->
                    log.info { "MessageAwaiter coroutine exception: $error" }
                }

    @OptIn(ExperimentalTime::class)
    private suspend fun isRelevanceContinue(histories: List<HistoryRecord>, currentBotId: String): Boolean {
        val promptExecutor by ref<PromptExecutor>()

        val prompt = prompt("__bot_chat__", LLMParams(maxTokens = 16384)) {
            system(
                """
            你是一个群聊行为判断器。
            任务：判断机器人在当前群聊中是否应该继续发言。
            
            输入：按时间顺序的聊天记录，每条包含【发言者昵称】+【消息内容】
            标注：*标记的消息是机器人的发言
            
            重要概念：
            - 观察窗口 = 机器人最后一条消息之后的所有新消息
            - 判断焦点 = 观察窗口中的【最新消息】是否与机器人相关
            
            判定优先级（从高到低）：
            
            【触发 CONTINUE】条件（满足任一即可）：
            1. 消息直接称呼机器人名字/昵称
            2. 消息直接向机器人提问（含问句符号？或语义提问）
            3. 消息要求机器人执行动作/提供服务
            4. 消息延续机器人刚才提出的话题/问题
            5. 消息是对机器人消息的实质性回答或补充
            6. 消息在解释机器人可能不理解的原因（如："为什么这么说"）
            
            【触发 SILENT】条件（满足任一即可）：
            1. 观察窗口为空（机器人后无新消息）
            2. 纯文件等无法文本互动的内容
            3. 明显是群友间闲聊，与机器人无关
            4. 纯感谢/结束语（"谢谢"、"好的"、"明白了"、"已解决"）
            5. 话题已自然结束，无继续讨论必要
            6. 消息属于刷屏、广告、无关内容
            
            【默认规则】：
            - 无法确定时 → SILENT（保守原则）
            - 观察窗口有多条消息时，以【最接近机器人的第一条】为主要判断依据
            - 如果机器人后第一条是感谢，后续又有新话题 → 根据后续内容判断
            
            输出格式：
            只输出一个词：CONTINUE 或 SILENT
            禁止输出任何解释或额外内容
            """.trimIndent()
            )
            user {
                text("根据以下群聊记录判断机器人是否应该继续发言。只输出 CONTINUE 或 SILENT。")
                text {
                    markdown {
                        buildHistoriesPrompt(histories, currentBotId)
                    }
                }
            }
        }

        val response = promptExecutor.execute(prompt, LLMModelChoice.Flash)
        val content = response.textContent()
        log.info { "Relevance continue determine LLM response: $content" }
        return content.contains("CONTINUE")
    }

}

internal fun HistoryRecord.isMessageFor(botId: String, groupId: String): Boolean =
    this.botId == botId && this.groupId == groupId && userId != botId
