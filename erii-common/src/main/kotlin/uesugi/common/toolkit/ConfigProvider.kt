package uesugi.common.toolkit

import com.typesafe.config.Config
import kotlinx.serialization.json.JsonElement
import kotlin.reflect.KClass

/**
 * 群组配置数据类
 */
data class GroupConfig(
    val admins: List<String>,
    val desire: Double
)

/**
 * Bot 维度的群组配置覆盖（可选），未指定字段时回退到全局 groups 配置
 */
data class BotGroupsOverride(
    val enableGroups: List<String>? = null,
    val disablePrivate: Boolean? = null
)

/**
 * Bot 配置数据类
 */
data class BotConfig(
    val ws: String,
    val token: String,
    val roleId: String,
    val selfId: String? = null,
    val admins: List<String> = emptyList(),
    val groups: Map<String, GroupConfig> = emptyMap(),
    val groupsOverride: BotGroupsOverride? = null,
    val enabledPlugins: List<String>? = null,
    val disabledPlugins: List<String>? = null,
    val externalHost: String? = null
)

/**
 * 状态系统调参总配置。
 *
 * 用于集中控制情绪、心流、冲动值、记忆整理、摘要、表情包和热词进化等状态模块的数值策略。
 */
data class StateTuningConfig(
    /** 状态任务的事件合并、触发频率和并发配置。 */
    val dispatch: StateDispatchTuningConfig = StateDispatchTuningConfig(),
    /** 情绪与 mood 的更新、保留和时间衰减配置。 */
    val emotion: EmotionTuningConfig = EmotionTuningConfig(),
    /** 心流值的下限、增长、扣减、衰减和状态阈值配置。 */
    val flow: FlowTuningConfig = FlowTuningConfig(),
    /** 主动发言冲动值的基础欲望、刺激、疲劳、阈值和权重配置。 */
    val volition: VolitionTuningConfig = VolitionTuningConfig(),
    /** 事实记忆和用户画像整理的批量与触发门槛配置。 */
    val memory: MemoryTuningConfig = MemoryTuningConfig(),
    /** 聊天摘要整理的批量、触发门槛和保留时间配置。 */
    val summary: SummaryTuningConfig = SummaryTuningConfig(),
    /** 表情包收集、分析和清理的阈值配置。 */
    val meme: MemeTuningConfig = MemeTuningConfig(),
    /** 热词权重增长、衰减、淘汰和活跃词表输出配置。 */
    val evolution: EvolutionTuningConfig = EvolutionTuningConfig()
)

enum class StateTriggerProfile {
    REALTIME,
    BALANCED,
    ECONOMY;

    companion object {
        fun parse(value: String?): StateTriggerProfile =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: ECONOMY
    }
}

/**
 * 资源清理调参配置。
 */
data class ResourceCleanupConfig(
    /** 资源保留天数，超过此天数的资源将被清理（被表情包引用的除外）。 */
    val retentionDays: Int = 15,
    /** 缩略图保留天数，默认 30。 */
    val thumbnailRetentionDays: Int = 30
)

data class StateDispatchTuningConfig(
    val profile: StateTriggerProfile = StateTriggerProfile.ECONOMY,
    val maxConcurrency: Int = 4
)

/**
 * 情绪系统调参配置。
 */
data class EmotionTuningConfig(
    /** 高保留强度下，旧情绪在单次更新中保留的比例；越大越稳定，越小越容易被新情绪覆盖。 */
    val emotionRetentionHigh: Double = 0.85,
    /** 中保留强度下，旧情绪在单次更新中保留的比例。 */
    val emotionRetentionMedium: Double = 0.7,
    /** 低保留强度下，旧情绪在单次更新中保留的比例；用于强烈新事件快速改写当前情绪。 */
    val emotionRetentionLow: Double = 0.5,
    /** mood 在单次更新中保留历史值的比例；越大 mood 越平滑，越小变化越灵敏。 */
    val moodRetention: Double = 0.92,
    /** 当前情绪对 mood 的影响权重；越大 mood 越容易跟随最近情绪变化。 */
    val moodEmotionInfluence: Double = 0.7,
    /** 当前情绪随时间自然衰减的半衰期，单位秒。 */
    val emotionHalfLifeSeconds: Long = 1200,
    /** mood 随时间自然衰减的半衰期，单位秒。 */
    val moodHalfLifeSeconds: Long = 3600
)

/**
 * 心流系统调参配置。
 */
data class FlowTuningConfig(
    /** 心流最低值相对基础欲望值的比例，用于避免高欲望群组心流过低。 */
    val minRatioOfDesire: Double = 0.3,
    /** 心流最低值的硬下限。 */
    val minValueMin: Double = 1.0,
    /** 心流最低值的硬上限。 */
    val minValueMax: Double = 20.0,
    /** 普通状态下心流每分钟自然衰减量。 */
    val decayNormalPerMinute: Double = 0.3,
    /** 负面心流状态下每分钟额外衰减量。 */
    val decayNegativePerMinute: Double = 1.5,
    /** 命中核心兴趣事件时增加的基础心流值。 */
    val coreInterestBaseCharge: Double = 20.0,
    /** 连续互动事件时增加的基础心流值。 */
    val continuousInteractionBaseCharge: Double = 10.0,
    /** 深度回复事件时增加的基础心流值。 */
    val deepReplyBaseCharge: Double = 5.0,
    /** 群体共鸣事件时增加的基础心流值。 */
    val groupResonanceBaseCharge: Double = 10.0,
    /** 负面反馈事件扣减的心流值。 */
    val negativePenalty: Double = 40.0,
    /** 话题被打断时扣减的心流值。 */
    val topicInterruptPenalty: Double = 30.0,
    /** 重复话题时扣减的心流值。 */
    val repeatTopicPenalty: Double = 10.0,
    /** 群活跃度低时扣减的心流值。 */
    val lowActivityPenalty: Double = 5.0,
    /** 心流进入变好状态的阈值。 */
    val gettingBetterThreshold: Double = 30.0,
    /** 心流进入爆发状态的阈值。 */
    val burstThreshold: Double = 70.0
)

/**
 * 主动发言冲动值调参配置。
 */
data class VolitionTuningConfig(
    /** 群组未配置 desire 时使用的默认基础欲望值。 */
    val baseDesireDefault: Double = 15.0,
    /** 每日定时主动发言的基准时间，格式为 HH:mm；空列表表示关闭定时发言。 */
    val dailyTriggerTimes: List<String> = listOf("15:00", "20:00"),
    /** 每日定时触发在基准时间之后的随机抖动上限，单位分钟。 */
    val dailyTriggerJitterMinutes: Int = 30,
    /** 静默群监控的轮询间隔，单位分钟。 */
    val silentMonitorIntervalMinutes: Long = 10,
    /** 群聊持续静默达到该时长后可触发主动发言，单位小时。 */
    val silentThresholdHours: Long = 4,
    /** 禁止静默触发的开始时间，格式为 HH:mm。 */
    val silentExcludedStartTime: String = "22:00",
    /** 禁止静默触发的结束时间，格式为 HH:mm，支持跨日时段。 */
    val silentExcludedEndTime: String = "08:00",
    /** 关键词命中可提供的最大刺激值，实际值会乘以关键词强度。 */
    val keywordHitMaxStimulus: Double = 30.0,
    /** 群聊繁忙时增加的刺激值。 */
    val busyGroupStimulus: Double = 10.0,
    /** 间接提及时增加的刺激值。 */
    val indirectMentionStimulus: Double = 25.0,
    /** 情绪共鸣时增加的刺激值。 */
    val emotionalResonanceStimulus: Double = 15.0,
    /** 周期性重置或补充时增加的刺激值。 */
    val resetStimulusAmount: Double = 15.0,
    /** 主动发言后增加的疲劳值，用于抑制连续主动说话。 */
    val fatigueOnSpeak: Double = 40.0,
    /** 低唤醒状态下每个衰减周期减少的疲劳值。 */
    val fatigueDecayLowArousal: Double = 2.0,
    /** 普通状态下每个衰减周期减少的疲劳值。 */
    val fatigueDecayNormal: Double = 1.0,
    /** 普通状态下每个衰减周期减少的刺激值。 */
    val stimulusDecayNormal: Double = 3.0,
    /** 高心流状态下每个衰减周期减少的刺激值；通常比普通状态更慢。 */
    val stimulusDecayHighFlow: Double = 1.0,
    /** 普通心流下触发主动发言的冲动值阈值。 */
    val normalSpeakThreshold: Double = 65.0,
    /** 高心流下触发主动发言的冲动值阈值。 */
    val highFlowSpeakThreshold: Double = 50.0,
    /** 判定为高心流的心流阈值。 */
    val highFlowThreshold: Double = 70.0,
    /** 情绪唤醒度对冲动值的加权系数。 */
    val arousalImpulseWeight: Double = 30.0,
    /** 负面愉悦度对冲动值的惩罚权重。 */
    val negativePleasurePenaltyWeight: Double = 20.0,
    /** 心流奖励开始生效的起点阈值。 */
    val flowBonusStart: Double = 70.0,
    /** 超过心流奖励起点后，每点心流转化为冲动加成的权重。 */
    val flowBonusWeight: Double = 1.0
)

/**
 * 记忆整理调参配置。
 */
data class MemoryTuningConfig(
    /** 单次事实记忆和用户画像整理最多读取的消息数量。 */
    val batchLimit: Int = 400,
    /** 累计未整理消息达到该数量后才触发记忆整理。 */
    val minMessages: Int = 30,
    /** 有效事实记忆超过该天数未被 getFactsWithVector 召回后，会被夜间清理任务删除。 */
    val staleRecallDays: Long = 30
)

/**
 * 摘要整理调参配置。
 */
data class SummaryTuningConfig(
    /** 单次摘要整理最多读取的消息数量。 */
    val batchLimit: Int = 200,
    /** 累计未摘要消息达到该数量后才触发摘要整理。 */
    val minMessages: Int = 30,
    /** 摘要状态和相关历史保留天数。 */
    val retentionDays: Long = 7
)

/**
 * 表情包收集与分析调参配置。
 */
data class MemeTuningConfig(
    /** 表情包累计出现达到该次数后进入分析或复查流程。 */
    val analyzeThreshold: Int = 3,
    /** 单个表情包最多保留的上下文数量。 */
    val maxContexts: Int = 400,
    /** 清理低热度或过期表情包时使用的保留天数。 */
    val cleanupDays: Int = 7,
    /** 低热度表情包需要达到该出现次数后才保留，否则可能被清理。 */
    val lowHeatSeenThreshold: Int = 3
)

/**
 * 热词进化调参配置。
 */
data class EvolutionTuningConfig(
    /** 新热词创建时的默认权重。 */
    val defaultWeight: Int = 50,
    /** 进入活跃词表需要达到的最低权重。 */
    val activeWeightThreshold: Int = 50,
    /** 热词低于该权重时会被淘汰或不再继续保留。 */
    val minWeightThreshold: Int = 20,
    /** 热词每次被正向使用时增加的权重。 */
    val increaseOnUse: Int = 10,
    /** 每个进化衰减周期减少的权重。 */
    val decayPerCycle: Int = 10,
    /** 热词收到负面反馈时减少的权重。 */
    val decreaseOnNegative: Int = 50,
    /** 热词超过该天数未使用后视为陈旧并参与衰减。 */
    val staleDays: Int = 3,
    /** 热词挖掘时读取最近消息的时间窗口，单位小时。 */
    val recentRangeHours: Long = 1,
    /** 热词挖掘时读取最近消息的最大数量。 */
    val recentMessageLimit: Int = 500,
    /** 默认输出到活跃词表的最大热词数量。 */
    val activeLimit: Int = 10
)

/**
 * OpenAI 客户端设置配置
 */
data class OpenAIClientConfig(
    val chatCompletionsPath: String = "v1/chat/completions",
    val responsesAPIPath: String = "v1/responses",
    val embeddingsPath: String = "v1/embeddings",
    val moderationsPath: String = "v1/moderations",
    val modelsPath: String = "v1/models"
)

/**
 * Anthropic 客户端设置配置
 */
data class AnthropicClientConfig(
    val apiVersion: String = "2023-06-01",
    val messagesPath: String = "v1/messages",
    val modelsPath: String = "v1/models"
)

/**
 * 配置提供者接口
 * 定义所有配置读取方法的契约
 */
interface ConfigProvider {
    // ===== LLM 配置 =====
    fun getLlmOpenAIApiKey(): String
    fun getLlmOpenAIBaseUrl(): String
    fun getLlmOpenAIModels(): Map<String, String>
    fun getLlmOpenAIClientConfig(): OpenAIClientConfig
    fun getLlmAnthropicApiKey(): String
    fun getLlmAnthropicBaseUrl(): String
    fun getLlmAnthropicModels(): Map<String, String>
    fun getLlmAnthropicClientConfig(): AnthropicClientConfig
    fun getChoiceProvider(): String
    fun isLlmCapabilityEnabled(name: String): Boolean
    fun isLlmCapabilityEnabled(tier: String, name: String): Boolean
    fun getLlmDefaultParams(): Map<String, Map<String, JsonElement>>
    fun isLlmProxyEnabled(): Boolean

    // ===== 第三方服务 =====
    fun getEmbeddingApiKey(): String
    fun getEmbeddingProvider(): String
    fun getEmbeddingUrl(): String
    fun getEmbeddingModel(): String
    fun getSearchApiKey(): String
    fun getSearchProvider(): String
    fun getSearchUrl(): String
    fun getSearchModel(): String
    fun getVisionApiKey(): String
    fun getVisionProvider(): String
    fun getVisionUrl(): String
    fun getVisionModel(): String
    fun getSttApiKey(): String
    fun getSttProvider(): String
    fun getSttUrl(): String
    fun getSttModel(): String

    // ===== 代理 =====
    fun getProxyHttp(): String?
    fun getProxySocks(): String?

    // ===== OneBot =====
    fun getOnebotWs(): String
    fun getOnebotToken(): String
    fun getOnebotBots(): Map<String, BotConfig>
    fun getAdmins(botConfigKey: String, groupId: String): List<String>

    // ===== Resource cleanup =====
    fun getResourceCleanup(): ResourceCleanupConfig

    // ===== State tuning =====
    fun getStateTuning(): StateTuningConfig

    // ===== Web =====
    fun getPlaywrightUrl(): String
    fun getBrowserDownload(): Boolean
    fun getBrowserExternalHost(): String

    // ===== 群组 =====
    fun getDisablePrivate(): Boolean
    fun getEnableGroups(): List<String>

    // ===== 群组（Bot 维度有效值，含覆盖逻辑）=====
    fun getEffectiveEnableGroups(botKey: String): List<String>
    fun getEffectiveDisablePrivate(botKey: String): Boolean
    fun isGroupEnabled(botKey: String, groupId: String): Boolean

    // ===== 插件配置 =====
    fun getPluginConfig(pluginClass: KClass<*>, pluginName: String): Config

    // ===== 插件启用/禁用（Bot 维度）=====
    fun getEnabledPlugins(botKey: String): List<String>?
    fun getDisabledPlugins(botKey: String): List<String>?
    fun isPluginEnabled(botKey: String, pluginName: String): Boolean

    // ===== Agent 配置 =====
    fun getAgentMaxMessageLength(): Int
    fun getAgentMaxIterations(): Int

    // ===== 通用配置读取 =====
    fun getString(path: String): String?
    fun getDouble(path: String): Double?

    // ===== 刷新缓存 =====
    fun refresh()
}

/**
 * 配置管理器，委托给 ConfigProvider 实现
 * 在应用启动时通过 init() 注入实现
 */
object ConfigHolder {

    private lateinit var provider: ConfigProvider

    fun init(provider: ConfigProvider) {
        this.provider = provider
    }

    // ===== LLM 配置 =====
    fun getLlmOpenAIApiKey(): String = provider.getLlmOpenAIApiKey()
    fun getLlmOpenAIBaseUrl(): String = provider.getLlmOpenAIBaseUrl()
    fun getLlmOpenAIModels(): Map<String, String> = provider.getLlmOpenAIModels()
    fun getLlmOpenAIClientConfig(): OpenAIClientConfig = provider.getLlmOpenAIClientConfig()
    fun getLlmAnthropicApiKey(): String = provider.getLlmAnthropicApiKey()
    fun getLlmAnthropicBaseUrl(): String = provider.getLlmAnthropicBaseUrl()
    fun getLlmAnthropicModels(): Map<String, String> = provider.getLlmAnthropicModels()
    fun getLlmAnthropicClientConfig(): AnthropicClientConfig = provider.getLlmAnthropicClientConfig()
    fun getChoiceProvider(): String = provider.getChoiceProvider()
    fun isLlmCapabilityEnabled(name: String): Boolean = provider.isLlmCapabilityEnabled(name)
    fun isLlmCapabilityEnabled(tier: String, name: String): Boolean = provider.isLlmCapabilityEnabled(tier, name)
    fun getLlmDefaultParams(): Map<String, Map<String, JsonElement>> = provider.getLlmDefaultParams()
    fun isLlmProxyEnabled(): Boolean = provider.isLlmProxyEnabled()

    // ===== 第三方服务 =====
    fun getEmbeddingApiKey(): String = provider.getEmbeddingApiKey()
    fun getEmbeddingProvider(): String = provider.getEmbeddingProvider()
    fun getEmbeddingUrl(): String = provider.getEmbeddingUrl()
    fun getEmbeddingModel(): String = provider.getEmbeddingModel()
    fun getSearchApiKey(): String = provider.getSearchApiKey()
    fun getSearchProvider(): String = provider.getSearchProvider()
    fun getSearchUrl(): String = provider.getSearchUrl()
    fun getSearchModel(): String = provider.getSearchModel()
    fun getVisionApiKey(): String = provider.getVisionApiKey()
    fun getVisionProvider(): String = provider.getVisionProvider()
    fun getVisionUrl(): String = provider.getVisionUrl()
    fun getVisionModel(): String = provider.getVisionModel()
    fun getSttApiKey(): String = provider.getSttApiKey()
    fun getSttProvider(): String = provider.getSttProvider()
    fun getSttUrl(): String = provider.getSttUrl()
    fun getSttModel(): String = provider.getSttModel()

    // ===== 代理 =====
    fun getProxyHttp(): String? = provider.getProxyHttp()
    fun getProxySocks(): String? = provider.getProxySocks()

    // ===== OneBot =====
    fun getOnebotWs(): String = provider.getOnebotWs()
    fun getOnebotToken(): String = provider.getOnebotToken()
    fun getOnebotBots(): Map<String, BotConfig> = provider.getOnebotBots()
    fun getAdmins(botConfigKey: String, groupId: String): List<String> = provider.getAdmins(botConfigKey, groupId)

    // ===== Resource cleanup =====
    fun getResourceCleanup(): ResourceCleanupConfig = provider.getResourceCleanup()

    // ===== State tuning =====
    fun getStateTuning(): StateTuningConfig = provider.getStateTuning()

    // ===== Web =====
    fun getPlaywrightUrl(): String = provider.getPlaywrightUrl()
    fun getBrowserDownload(): Boolean = provider.getBrowserDownload()
    fun getBrowserExternalHost(): String = provider.getBrowserExternalHost()

    // ===== 群组 =====
    fun getDisablePrivate(): Boolean = provider.getDisablePrivate()
    fun getEnableGroups(): List<String> = provider.getEnableGroups()

    // ===== 群组（Bot 维度有效值）=====
    fun getEffectiveEnableGroups(botKey: String): List<String> = provider.getEffectiveEnableGroups(botKey)
    fun getEffectiveDisablePrivate(botKey: String): Boolean = provider.getEffectiveDisablePrivate(botKey)
    fun isGroupEnabled(botKey: String, groupId: String): Boolean = provider.isGroupEnabled(botKey, groupId)

    // ===== 插件配置 =====
    fun getPluginConfig(pluginClass: KClass<*>, pluginName: String): Config =
        provider.getPluginConfig(pluginClass, pluginName)

    // ===== 插件启用/禁用（Bot 维度）=====
    fun getEnabledPlugins(botKey: String): List<String>? = provider.getEnabledPlugins(botKey)
    fun getDisabledPlugins(botKey: String): List<String>? = provider.getDisabledPlugins(botKey)
    fun isPluginEnabled(botKey: String, pluginName: String): Boolean = provider.isPluginEnabled(botKey, pluginName)

    // ===== Agent 配置 =====
    fun getAgentMaxMessageLength(): Int = provider.getAgentMaxMessageLength()
    fun getAgentMaxIterations(): Int = provider.getAgentMaxIterations()

    // ===== 通用配置读取 =====
    fun getString(path: String): String? = provider.getString(path)
    fun getDouble(path: String): Double? = provider.getDouble(path)

    // ===== 刷新缓存 =====
    fun refresh() = provider.refresh()
}
