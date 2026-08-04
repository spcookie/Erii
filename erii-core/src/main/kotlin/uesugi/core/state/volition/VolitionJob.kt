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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class VolitionJob(
    private val volitionAgent: VolitionAgent,
    private val volitionRepository: VolitionRepository
) : StateWorkProcessor {

    override val kind = StateWorkKind.VOLITION

    companion object {
        private val log = logger()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)

    fun openTimingTriggerSignal() {
        if (!started.compareAndSet(false, true)) {
            log.warn("Volition event processor is already initialized, skip duplicate startup")
            return
        }

        try {
            for (bot in BotManage.getAllBotIds()) {
                val configKey = BotManage.getConfigKey(bot)
                for (group in ConfigHolder.resolveEnabledGroups(configKey, bot)) {
                    log.info("init volition for bot $bot in group $group")
                    ensureVolitionGaugeExists(bot, group)
                }
            }
            log.info("Volition event processor initialized")

            startDailyTasks()
            startSilentMonitor()
        } catch (e: Exception) {
            started.set(false)
            throw e
        }
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

    private fun startDailyTasks() {
        val tuning = ConfigHolder.getStateTuning().volition
        val jitterMinutes = tuning.dailyTriggerJitterMinutes.coerceAtLeast(0)
        tuning.dailyTriggerTimes.forEach { configuredTime ->
            val triggerTime = parseVolitionScheduleTime(configuredTime)
            if (triggerTime == null) {
                log.warn("Ignore invalid daily volition trigger time '$configuredTime', expected HH:mm")
                return@forEach
            }
            scope.launchJitterDailyTask(
                baseHour = triggerTime.hour,
                baseMinute = triggerTime.minute,
                minOffsetMinutes = 0,
                maxOffsetMinutes = jitterMinutes,
            ) { triggerDailySpeak() }
        }
    }

    private fun triggerDailySpeak() {
        val volitionGaugeManager = GlobalContext.get().get<VolitionGaugeManager>()
        val targets = scheduledSpeakTargets(volitionGaugeManager)
        selectOneBotPerGroup(targets, ScheduledSpeakTarget::targetGroupId, ScheduledSpeakTarget::botId)
            .forEach { target ->
                log.info("Decision: Group ${target.targetGroupId} regularly speaks through bot ${target.botId}")
                speakV(
                    botId = target.botId,
                    groupId = target.targetGroupId,
                    interruptionMode = InterruptionMode.Routine
                )
            }
    }

    private fun scheduledSpeakTargets(
        volitionGaugeManager: VolitionGaugeManager
    ): List<ScheduledSpeakTarget> = volitionGaugeManager.getAllGauges().mapNotNull { (key, gauge) ->
        val (botId, sourceGroupId) = key.split(":", limit = 2)
            .takeIf { it.size == 2 }
            ?: run {
                log.warn("Ignore invalid volition gauge key: $key")
                return@mapNotNull null
            }
        try {
            val configKey = BotManage.getConfigKey(botId)
            if (!ConfigHolder.isGroupEnabled(configKey, sourceGroupId)) {
                return@mapNotNull null
            }
            ScheduledSpeakTarget(botId, sourceGroupId, gauge)
        } catch (e: Exception) {
            log.error("Failed to resolve scheduled speak target, botId=$botId, groupId=$sourceGroupId", e)
            null
        }
    }

    private fun startSilentMonitor() {
        val volitionGaugeManager = GlobalContext.get().get<VolitionGaugeManager>()
        val tuning = ConfigHolder.getStateTuning().volition
        val monitorInterval = tuning.silentMonitorIntervalMinutes.coerceAtLeast(1).minutes
        val silentThresholdHours = tuning.silentThresholdHours.coerceAtLeast(1)
        val excludedStart = parseVolitionScheduleTime(tuning.silentExcludedStartTime)
            ?: LocalTime(22, 0).also {
                log.warn(
                    "Invalid silent exclusion start time '${tuning.silentExcludedStartTime}', falling back to 22:00"
                )
            }
        val excludedEnd = parseVolitionScheduleTime(tuning.silentExcludedEndTime)
            ?: LocalTime(8, 0).also {
                log.warn(
                    "Invalid silent exclusion end time '${tuning.silentExcludedEndTime}', falling back to 08:00"
                )
            }
        scope.launch {
            while (isActive) {
                delay(monitorInterval)
                try {
                    triggerSilentSpeak(
                        volitionGaugeManager = volitionGaugeManager,
                        silentThresholdHours = silentThresholdHours,
                        excludedStart = excludedStart,
                        excludedEnd = excludedEnd,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("Silent volition monitor failed", e)
                }
            }
        }
    }

    private fun triggerSilentSpeak(
        volitionGaugeManager: VolitionGaugeManager,
        silentThresholdHours: Long,
        excludedStart: LocalTime,
        excludedEnd: LocalTime,
    ) {
        val now = System.currentTimeMillis()
        val currentTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).time
        if (isTimeInExcludedRange(currentTime, excludedStart, excludedEnd)) {
            return
        }

        val silentTargets = scheduledSpeakTargets(volitionGaugeManager)
            .groupBy(ScheduledSpeakTarget::targetGroupId)
            .values
            .flatMap { groupTargets ->
                val lastActiveTime = groupTargets.maxOf { it.gauge.state.lastActiveTime }
                if (now - lastActiveTime <= silentThresholdHours.hours.inWholeMilliseconds) {
                    return@flatMap emptyList()
                }

                // A single selected bot speaks for the group. Advance every gauge together so
                // the remaining bots do not trigger again on the next monitor tick.
                groupTargets.forEach { it.gauge.state.lastActiveTime = now }
                groupTargets
            }

        selectOneBotPerGroup(
            silentTargets,
            ScheduledSpeakTarget::targetGroupId,
            ScheduledSpeakTarget::botId,
        ).forEach { target ->
            log.info(
                "Decision: Group ${target.targetGroupId} has been silent for $silentThresholdHours hours, " +
                        "bot ${target.botId} takes the initiative to speak"
            )
            speakV(
                botId = target.botId,
                groupId = target.targetGroupId
            )
        }
    }

    private fun CoroutineScope.launchJitterDailyTask(
        baseHour: Int,
        baseMinute: Int,
        minOffsetMinutes: Int,
        maxOffsetMinutes: Int,
        task: suspend () -> Unit
    ) = launch {
        var scheduledDate: LocalDate? = null
        while (isActive) {
            val zone = TimeZone.currentSystemDefault()
            val now = Clock.System.now()
            val today = now.toLocalDateTime(zone).date
            var targetDate = scheduledDate?.takeIf { it >= today } ?: today

            var triggerTime = dailyTriggerTime(
                date = targetDate,
                zone = zone,
                baseHour = baseHour,
                baseMinute = baseMinute,
                minOffsetMinutes = minOffsetMinutes,
                maxOffsetMinutes = maxOffsetMinutes,
            )
            if (triggerTime <= now) {
                targetDate = targetDate.plus(DatePeriod(days = 1))
                triggerTime = dailyTriggerTime(
                    date = targetDate,
                    zone = zone,
                    baseHour = baseHour,
                    baseMinute = baseMinute,
                    minOffsetMinutes = minOffsetMinutes,
                    maxOffsetMinutes = maxOffsetMinutes,
                )
            }

            delay(triggerTime - now)
            try {
                task()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Daily volition task failed at $triggerTime", e)
            }
            scheduledDate = targetDate.plus(DatePeriod(days = 1))
        }
    }

    private fun dailyTriggerTime(
        date: LocalDate,
        zone: TimeZone,
        baseHour: Int,
        baseMinute: Int,
        minOffsetMinutes: Int,
        maxOffsetMinutes: Int,
    ) = LocalDateTime(date, LocalTime(baseHour, baseMinute))
        .toInstant(zone)
        .plus(Random.nextInt(minOffsetMinutes, maxOffsetMinutes + 1).minutes)
}

private data class ScheduledSpeakTarget(
    val botId: String,
    val targetGroupId: String,
    val gauge: VolitionGauge,
)

internal fun parseVolitionScheduleTime(value: String): LocalTime? {
    val match = Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$").matchEntire(value.trim()) ?: return null
    val (hour, minute) = match.value.split(":").map(String::toInt)
    return LocalTime(hour, minute)
}

internal fun isTimeInExcludedRange(
    current: LocalTime,
    start: LocalTime,
    end: LocalTime,
): Boolean = when {
    start == end -> false
    start < end -> current >= start && current < end
    else -> current >= start || current < end
}

internal fun <T> selectOneBotPerGroup(
    candidates: Iterable<T>,
    groupId: (T) -> String,
    botId: (T) -> String,
    random: Random = Random.Default,
): List<T> = candidates
    .groupBy(groupId)
    .values
    .mapNotNull { groupCandidates ->
        groupCandidates.distinctBy(botId).randomOrNull(random)
    }
