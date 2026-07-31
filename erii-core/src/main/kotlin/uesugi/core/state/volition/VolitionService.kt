package uesugi.core.state.volition

import kotlinx.coroutines.*
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uesugi.common.BotManage
import uesugi.common.EventBus
import uesugi.common.data.EmotionalTendencies
import uesugi.common.event.InterruptionMode
import uesugi.common.event.PSFeature.CHAT_URGENT
import uesugi.common.event.PSFeature.GRAB
import uesugi.common.event.PSFeature.IGNORE_INTERRUPT
import uesugi.common.event.ProactiveSpeakEvent
import uesugi.common.toolkit.ConfigHolder
import uesugi.common.toolkit.VolitionTuningConfig
import uesugi.common.toolkit.logger
import uesugi.core.route.MetaToolSetRegister
import uesugi.core.state.emotion.EmotionChangeEvent
import uesugi.core.state.flow.FlowChangeEvent
import uesugi.plugin.MetaImpl
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class VolitionState(
    var fatigue: Double = 0.0,
    var stimulus: Double = 0.0,
    var lastActiveTime: Long = System.currentTimeMillis()
) {
    fun addFatigue(amount: Double) {
        fatigue = (fatigue + amount).coerceIn(0.0, 100.0)
    }

    fun addStimulus(amount: Double) {
        stimulus = (stimulus + amount).coerceIn(0.0, 100.0)
    }

    fun minusStimulus(amount: Double) {
        stimulus = (stimulus - amount).coerceIn(0.0, 100.0)
    }

    fun decayFatigue(amount: Double) {
        fatigue = (fatigue - amount).coerceIn(0.0, 100.0)
    }

    fun decayStimulus(amount: Double) {
        stimulus = (stimulus - amount).coerceIn(0.0, 100.0)
    }
}

class VolitionGauge(
    private var mood: EmotionalTendencies,
    private val botId: String,
    private val groupId: String,
    private val baseDesire: Double = 15.0,
    private val tuning: VolitionTuningConfig = VolitionTuningConfig(),
    private val decayIntervalMs: Long = 1000 * 60L,
    private val persistIntervalMs: Long = 1000 * 60L
) {
    val state = VolitionState()
    private val scope = CoroutineScope(Dispatchers.Default)

    private var pleasure: Double = mood.pad.normalize().p
    private var arousal: Double = mood.pad.normalize().a
    private var flowValue: Double = 0.0

    companion object {
        private val log = logger()
    }

    init {
        loadStateFromDB()

        scope.launch {
            while (isActive) {
                delay(decayIntervalMs.milliseconds)
                decayFatigue()
                decayStimulus()
            }
        }

        scope.launch {
            while (isActive) {
                delay(persistIntervalMs.milliseconds)
                persistStateToDB()
            }
        }

        scope.launch {
            while (isActive) {
                delay(decayIntervalMs.milliseconds)
                val gauge = this@VolitionGauge
                if (gauge.shouldSpeak()) {
                    log.info("决策: 机器人 $botId 群组 $groupId 应该主动发言!")

                    EventBus.postAsync(
                        ProactiveSpeakEvent(
                            botId = botId,
                            _groupId = groupId,
                            interruptionMode = InterruptionMode.Interrupt,
                        )
                    )

                    gauge.addFatigue(tuning.fatigueOnSpeak)
                    gauge.state.lastActiveTime = System.currentTimeMillis()
                }
            }
        }

        subscribe()
    }

    fun getMood() = mood

    private fun loadStateFromDB() {
        try {
            transaction {
                val volitionState = VolitionStateEntity.find {
                    (VolitionStateTable.botId eq botId) and (VolitionStateTable.groupId eq groupId)
                }.orderBy(VolitionStateTable.lastProcessedAt to SortOrder.DESC).firstOrNull()

                if (volitionState != null) {
                    state.fatigue = volitionState.fatigue
                    state.stimulus = volitionState.stimulus
                    state.lastActiveTime = volitionState.lastActiveTime
                    log.debug("从数据库加载主动意愿状态, botId=$botId, groupId=$groupId, fatigue=${state.fatigue}, stimulus=${state.stimulus}")
                } else {
                    state.stimulus = baseDesire
                    log.debug("群组 botId=$botId, groupId=$groupId 没有主动意愿状态记录, 使用默认值, stimulus=$baseDesire")
                }
            }
        } catch (e: Exception) {
            log.error("加载主动意愿状态失败, botId=$botId, groupId=$groupId", e)
        }
    }

    private fun persistStateToDB() {
        try {
            transaction {
                val volitionState = VolitionStateEntity.find {
                    (VolitionStateTable.botId eq botId) and (VolitionStateTable.groupId eq groupId)
                }.orderBy(VolitionStateTable.lastProcessedAt to SortOrder.DESC).firstOrNull()

                if (volitionState != null) {
                    volitionState.fatigue = state.fatigue
                    volitionState.stimulus = state.stimulus
                    volitionState.lastActiveTime = state.lastActiveTime
                    log.debug("持久化主动意愿状态, botId=$botId, groupId=$groupId, fatigue=${state.fatigue}, stimulus=${state.stimulus}")
                }
            }
        } catch (e: Exception) {
            log.error("持久化主动意愿状态失败, botId=$botId, groupId=$groupId", e)
        }
    }

    private fun subscribe() {
        EventBus.subscribeAsync<VolitionEvent>(scope) { event ->
            if (event.botId != botId || event.groupId != groupId) {
                return@subscribeAsync
            }

            when (event) {
                is ResetStimulusEvent -> minusStimulus(event.stimulus)
                is KeywordHitEvent -> if (arousal > 0.3) addStimulus(event.stimulus)
                is BusyGroupEvent -> addStimulus(event.stimulus)
                is IndirectMentionEvent -> addStimulus(event.stimulus)
                is EmotionalResonanceEvent -> addStimulus(event.stimulus)
            }
        }

        EventBus.subscribeAsync<EmotionChangeEvent>(scope) { event ->
            if (event.botId != botId || event.groupId != groupId) {
                return@subscribeAsync
            }
            mood = EmotionalTendencies.findClosest(event.pad)
            val (p, a, _) = event.pad.normalize()
            pleasure = p
            arousal = a
            log.debug("Volition收到情绪变更事件, botId=$botId, groupId=$groupId, P: $p , A: $a")
        }

        EventBus.subscribeAsync<FlowChangeEvent>(scope) { event ->
            if (event.botId != botId || event.groupId != groupId) {
                return@subscribeAsync
            }
            flowValue = event.value
        }
    }

    fun calculateRawImpulse(): Double {
        val emotionModifier = arousal * tuning.arousalImpulseWeight -
                maxOf(0.0, -pleasure * tuning.negativePleasurePenaltyWeight)
        val flowBonus = if (flowValue > tuning.flowBonusStart) {
            (flowValue - tuning.flowBonusStart) * tuning.flowBonusWeight
        } else 0.0

        val stimulus = state.stimulus
        val impulse = baseDesire.coerceIn(0.0, 100.0) + stimulus + emotionModifier + flowBonus

        return impulse.coerceIn(0.0, 100.0)
    }

    fun calculateImpulse(): Double {
        return (calculateRawImpulse() - state.fatigue).coerceIn(0.0, 100.0)
    }

    fun minusStimulus(amount: Double) {
        state.minusStimulus(amount)
        log.debug("刺激值重置: $amount, botId=$botId, groupId=$groupId")
    }

    fun addStimulus(amount: Double) {
        state.addStimulus(amount)
        log.debug("刺激值增加: +$amount, 当前刺激值: ${state.stimulus}, botId=$botId, groupId=$groupId")
    }

    fun addFatigue(amount: Double) {
        state.addFatigue(amount)
        log.debug("疲劳值增加: +$amount, 当前疲劳值: ${state.fatigue}, botId=$botId, groupId=$groupId")
    }

    fun decayFatigue() {
        val decayRate = if (arousal < 0.2) tuning.fatigueDecayLowArousal else tuning.fatigueDecayNormal
        state.decayFatigue(decayRate)
    }

    fun decayStimulus() {
        val decayRate =
            if (flowValue > tuning.highFlowThreshold) tuning.stimulusDecayHighFlow else tuning.stimulusDecayNormal
        state.decayStimulus(decayRate)
    }

    fun shouldSpeak(): Boolean {
        val impulse = calculateImpulse()

        val threshold = if (flowValue > tuning.highFlowThreshold) {
            tuning.highFlowSpeakThreshold
        } else {
            tuning.normalSpeakThreshold
        }

        return impulse > threshold
    }

    fun flush() {
        persistStateToDB()
    }

    fun stop() {
        persistStateToDB()
        scope.cancel()
    }
}

class VolitionGaugeManager {
    private val gauges = ConcurrentHashMap<String, VolitionGauge>()

    companion object {
        private val log = logger()
    }

    fun getOrCreate(botId: String, groupId: String, mood: EmotionalTendencies): VolitionGauge {
        val key = "$botId:$groupId"
        return gauges.getOrPut(key) {
            log.debug("创建新的VolitionGauge实例, botId=$botId, groupId=$groupId")
            val configKey = BotManage.getConfigKey(botId)
            val onebotBots = ConfigHolder.getOnebotBots()
            val tuning = ConfigHolder.getStateTuning().volition
            val desire = onebotBots[configKey]?.let {
                it.groups[groupId]?.desire
            } ?: tuning.baseDesireDefault
            VolitionGauge(mood, botId, groupId, desire, tuning)
        }
    }

    fun get(botId: String, groupId: String): VolitionGauge? {
        val key = "$botId:$groupId"
        return gauges[key]
    }

    fun getAllGauges(): Map<String, VolitionGauge> {
        return gauges.toMap()
    }

    fun stopAll() {
        gauges.values.forEach { it.stop() }
        gauges.clear()
        log.debug("所有VolitionGauge实例已关闭")
    }
}

@OptIn(ExperimentalUuidApi::class)
fun speakV(
    botId: String,
    groupId: String,
    senderId: String? = null,
    input: String? = null,
    echo: String? = null,
    interruptionMode: InterruptionMode = InterruptionMode.Interrupt,
) {
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
    val event = ProactiveSpeakEvent(
        botId = botId,
        _groupId = groupId,
        senderId = senderId,
        chatVision = true,
        webSearch = true,
        interruptionMode = interruptionMode,
        feature = CHAT_URGENT or GRAB or IGNORE_INTERRUPT,
        input = input,
        echo = echo ?: Uuid.random().toHexString(),
        toolSetBuilder = { metaToolSets }
    )
    EventBus.postAsync(event)
}
