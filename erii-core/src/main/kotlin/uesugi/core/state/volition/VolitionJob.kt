package uesugi.core.state.volition

import kotlinx.coroutines.*
import kotlinx.datetime.*
import org.koin.core.context.GlobalContext
import uesugi.common.BotManage
import uesugi.common.EventBus
import uesugi.common.data.HistoryRecord
import uesugi.common.data.MessageType
import uesugi.common.event.InterruptionMode
import uesugi.common.toolkit.ConfigHolder
import uesugi.common.toolkit.logger
import uesugi.core.component.usage.UsageContext
import uesugi.core.message.history.orEmptyTruncatedHistoryContent
import uesugi.core.state.dispatch.*
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

class VolitionJob(
    private val volitionAgent: VolitionAgent,
    private val volitionRepository: VolitionRepository
) : StateWorkProcessor {

    override val kind = StateWorkKind.VOLITION

    companion object {
        private val log = logger()
    }

    private val scope = CoroutineScope(Dispatchers.Default)

    fun openTimingTriggerSignal() {
        for (bot in BotManage.getAllBotIds()) {
            val configKey = BotManage.getConfigKey(bot)
            for (group in ConfigHolder.getEffectiveEnableGroups(configKey)) {
                log.info("init volition for bot $bot in group $group")
                ensureVolitionGaugeExists(bot, group)
            }
        }
        log.info("Volition event processor initialized")

        startDailyTasks()
        startSilentMonitor()
    }

    override fun accepts(record: HistoryRecord): Boolean = record.messageType == MessageType.TEXT

    override fun pendingKeys(): Set<StateWorkKey> = buildSet {
        for (botId in BotManage.getAllBotIds()) {
            volitionRepository.findGroupsNeedProcessing(botId).forEach { groupId ->
                add(StateWorkKey(botId, groupId, kind))
            }
        }
    }

    override suspend fun process(
        key: StateWorkKey,
        policy: StateWorkPolicy,
        force: Boolean
    ): StateWorkResult {
        ensureVolitionGaugeExists(key.botId, key.groupId)
        return UsageContext.withUsage(key.botId, key.groupId) {
            processGroupVolition(key.botId, key.groupId, policy, force)
        }
    }

    private fun ensureVolitionGaugeExists(botId: String, groupId: String) {
        val volitionGaugeManager = GlobalContext.get().get<VolitionGaugeManager>()
        volitionGaugeManager.getOrCreate(botId, groupId, BotManage.getBot(botId).role.emoticon)
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun processGroupVolition(
        botId: String,
        groupId: String,
        policy: StateWorkPolicy,
        force: Boolean
    ): StateWorkResult {
        log.debug("开始处理群组主动意愿, groupId=$groupId")

        try {
            val volitionState = withContext(Dispatchers.IO) {
                volitionRepository.getVolitionState(botId, groupId)
            }
            val lastId = volitionState?.lastProcessedHistoryId ?: 0

            val histories = withContext(Dispatchers.IO) {
                volitionRepository.getLatestHistoriesToProcess(botId, groupId, lastId, policy.batchLimit)
            }

            if (histories.isEmpty() || (!force && histories.size < policy.minMessages)) {
                return StateWorkResult(0, lastId, hasMore = false)
            }

            log.debug("群组 $groupId 获取到 ${histories.size} 条新消息")

            val maxMessageLength = ConfigHolder.getAgentMaxMessageLength()
            val messages = histories.map {
                VolitionMessage(
                    id = it.id.value,
                    botId = botId,
                    groupId = it.groupId,
                    userId = it.userId,
                    time = it.createdAt,
                    content = it.content.orEmptyTruncatedHistoryContent(maxMessageLength)
                )
            }

            if (messages.isEmpty()) {
                log.debug("群组 $groupId 消息转换后为空, 跳过处理")
                val maxHistoryId = histories.maxOf { it.id.value }
                withContext(Dispatchers.IO) {
                    volitionRepository.updateVolitionState(botId, groupId, maxHistoryId)
                }
                return StateWorkResult(histories.size, maxHistoryId, hasMore = false)
            }

            val success = analyze(botId, groupId, messages)
            if (!success) {
                throw IllegalStateException("Volition analysis failed, botId=$botId, groupId=$groupId")
            }

            val maxHistoryId = histories.maxOf { it.id.value }
            withContext(Dispatchers.IO) {
                volitionRepository.updateVolitionState(botId, groupId, maxHistoryId)
            }

            log.debug("群组 $groupId 主动意愿处理完成, 最大 historyId=$maxHistoryId")
            return StateWorkResult(histories.size, maxHistoryId, hasMore = false)

        } catch (e: Exception) {
            log.error("Processing group $groupId voluntary request failed", e)
            throw e
        }
    }

    private suspend fun analyze(
        botId: String,
        groupId: String,
        messages: List<VolitionMessage>
    ): Boolean {
        val volitionGaugeManager = GlobalContext.get().get<VolitionGaugeManager>()
        val gauge = volitionGaugeManager.get(botId, groupId) ?: return false

        gauge.state.lastActiveTime = messages.maxOf { it.time }
            .toInstant(TimeZone.currentSystemDefault())
            .toEpochMilliseconds()

        val botInterests = BotManage.getBot(botId).role.character

        val result = volitionAgent.analysis(messages, botInterests, gauge.getMood()) ?: return false
        val tuning = ConfigHolder.getStateTuning().volition

        log.info("Impulsive value analysis completed, botId=$botId, groupId=$groupId, $result")

        EventBus.postAsync(ResetStimulusEvent(botId, groupId, tuning.resetStimulusAmount))

        if (result.keywordHit) {
            EventBus.postAsync(
                KeywordHitEvent(
                    botId,
                    groupId,
                    result.keywordStrength.coerceIn(0.0, 1.0) * tuning.keywordHitMaxStimulus
                )
            )
        }

        if (result.isBusy) {
            EventBus.postAsync(BusyGroupEvent(botId, groupId, tuning.busyGroupStimulus))
        }

        if (result.indirectMention) {
            EventBus.postAsync(IndirectMentionEvent(botId, groupId, tuning.indirectMentionStimulus))
        }

        if (result.emotionalResonance) {
            EventBus.postAsync(EmotionalResonanceEvent(botId, groupId, tuning.emotionalResonanceStimulus))
        }

        return true
    }

    @OptIn(ExperimentalTime::class)
    private fun startDailyTasks() {
        scope.launch {
            launchJitterDailyTask(
                baseHour = 15,
                baseMinute = 0,
                minOffsetMinutes = 0,
                maxOffsetMinutes = 30
            ) { triggerDailySpeak() }

            launchJitterDailyTask(
                baseHour = 20,
                baseMinute = 0,
                minOffsetMinutes = 0,
                maxOffsetMinutes = 30
            ) { triggerDailySpeak() }
        }
    }

    private fun triggerDailySpeak() {
        val volitionGaugeManager = GlobalContext.get().get<VolitionGaugeManager>()
        volitionGaugeManager.getAllGauges().forEach { (key, _) ->
            val (botId, groupId) = key.split(":")
            val configKey = BotManage.getConfigKey(botId)
            val effectiveGroups = ConfigHolder.getEffectiveEnableGroups(configKey)
            val effectiveRedirect = ConfigHolder.getEffectiveMessageRedirectMap(configKey)
            if (effectiveGroups.contains(groupId)) {
                val groupId = effectiveRedirect.getOrDefault(groupId, groupId)
                log.info("Decision: Group $groupId speaks regularly")
                speakV(
                    botId = botId,
                    groupId = groupId,
                    interruptionMode = InterruptionMode.Routine
                )
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun startSilentMonitor() {
        val volitionGaugeManager = GlobalContext.get().get<VolitionGaugeManager>()
        scope.launch {
            while (isActive) {
                delay(10.minutes)

                val now = System.currentTimeMillis()

                volitionGaugeManager.getAllGauges().forEach { (key, gauge) ->
                    val (botId, groupId) = key.split(":")
                    val configKey = BotManage.getConfigKey(botId)
                    val effectiveGroups = ConfigHolder.getEffectiveEnableGroups(configKey)
                    val effectiveRedirect = ConfigHolder.getEffectiveMessageRedirectMap(configKey)
                    if (effectiveGroups.contains(groupId)) {
                        val groupId = effectiveRedirect.getOrDefault(groupId, groupId)
                        if (now - gauge.state.lastActiveTime > 4.hours.inWholeMilliseconds) {
                            gauge.state.lastActiveTime = now

                            val dateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                            val hour = dateTime.hour
                            val inRange = hour in 8 until 22
                            if (inRange) {
                                log.info("Decision: No message from group $groupId within 4 hours, take the initiative to speak")
                                speakV(
                                    botId = botId,
                                    groupId = groupId
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun CoroutineScope.launchJitterDailyTask(
        baseHour: Int,
        baseMinute: Int,
        minOffsetMinutes: Int,
        maxOffsetMinutes: Int,
        task: suspend () -> Unit
    ) = launch {
        while (isActive) {
            val zone = TimeZone.currentSystemDefault()
            val now = Clock.System.now()

            val today = now.toLocalDateTime(zone).date

            val baseTime = LocalDateTime(
                date = today,
                time = LocalTime(baseHour, baseMinute)
            )

            val offset = Random.nextInt(
                minOffsetMinutes,
                maxOffsetMinutes + 1
            )

            val triggerTime = baseTime
                .toInstant(zone)
                .plus(offset.minutes)

            val delayMillis = triggerTime - now

            if (delayMillis.isPositive()) {
                delay(delayMillis)
                task()
            }

            delay(1.minutes)
        }
    }
}
