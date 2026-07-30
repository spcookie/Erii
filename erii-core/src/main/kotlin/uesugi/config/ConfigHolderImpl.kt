package uesugi.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import com.typesafe.config.ConfigValueFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.config.*
import kotlinx.serialization.json.*
import uesugi.common.toolkit.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

class ConfigHolderImpl : ConfigProvider {

    private val log = KotlinLogging.logger {}

    private val pluginConfigCache = ConcurrentHashMap<String, Config>()

    private val configPath: String? by lazy {
        val raw = System.getProperty("config.path")
            ?: System.getenv("CONFIG_PATH")
            ?: "application.conf"
        File(raw).toPath().toAbsolutePath().toString()
    }

    private val pluginConfigDir: String? by lazy {
        val raw = System.getProperty("config.plugin.dir")
            ?: System.getenv("CONFIG_PLUGIN_DIR")
            ?: ((System.getProperty("pf4j.pluginsDir") ?: "plugins") + File.separator + "config")
        File(raw).toPath().toAbsolutePath().toString()
    }

    @Volatile
    private var _config: Config? = null

    private val config: Config get() = _config ?: loadConfig().also { _config = it }

    private val resolveOptions: ConfigResolveOptions
        get() = ConfigResolveOptions.defaults().setUseSystemEnvironment(true)

    private fun loadConfig(): Config {
        val classpathConfig = ConfigFactory.load("application.conf").resolve(resolveOptions)
        val fileConfig = configPath?.let { p ->
            ConfigFactory.parseFile(File(p)).resolve(resolveOptions)
        } ?: ConfigFactory.empty()
        val baseConfig = fileConfig.withFallback(classpathConfig)
        var cfg = baseConfig
        cfg = overrideWithSystemProperties(cfg)
        log.info { "Config loaded successfully" }
        return cfg
    }

    private fun overrideWithSystemProperties(base: Config): Config {
        val overrides = mutableMapOf<String, Any>()
        System.getProperties().stringPropertyNames()
            .forEach { key ->
                val value = System.getProperty(key)
                overrides[key] = value
            }
        if (overrides.isEmpty()) return base
        var overrideConfig = ConfigFactory.empty()
        overrides.forEach { (path, value) ->
            overrideConfig = overrideConfig.withValue(
                path,
                ConfigValueFactory.fromAnyRef(value, "system property: $path")
            )
        }
        return base.withFallback(overrideConfig)
    }

    private fun getLlmModelsHierarchical(provider: String): Map<String, String> {
        val hierarchicalPath = "llm.providers.$provider.models"
        val hierarchicalConfig = config.getConfig(hierarchicalPath)
        val result = mutableMapOf<String, String>()
        // Read all tier keys from config
        hierarchicalConfig.root().keys.forEach { key ->
            val keyStr = key.toString()
            if (keyStr != "all") {
                result[keyStr] = hierarchicalConfig.getString(keyStr)
            }
        }
        // "all" override logic
        val allOverride = hierarchicalConfig.tryGetString("all")
        if (!allOverride.isNullOrBlank()) {
            result.keys.forEach { tier -> result[tier] = allOverride }
        }
        return result
    }

    override fun getLlmOpenAIApiKey(): String = config.getString("llm.providers.openai.api-key")
    override fun getLlmOpenAIBaseUrl(): String = config.getString("llm.providers.openai.base-url")
    override fun getLlmOpenAIModels(): Map<String, String> =
        getLlmModelsHierarchical("openai")

    override fun getLlmOpenAIClientConfig(): OpenAIClientConfig {
        val p = "llm.providers.openai.settings"
        return OpenAIClientConfig(
            chatCompletionsPath = config.tryGetString("$p.chat-completions") ?: "v1/chat/completions",
            responsesAPIPath = config.tryGetString("$p.responses-api") ?: "v1/responses",
            embeddingsPath = config.tryGetString("$p.embeddings") ?: "v1/embeddings",
            moderationsPath = config.tryGetString("$p.moderations") ?: "v1/moderations",
            modelsPath = config.tryGetString("$p.models") ?: "v1/models"
        )
    }

    override fun getLlmAnthropicApiKey(): String = config.getString("llm.providers.anthropic.api-key")
    override fun getLlmAnthropicBaseUrl(): String = config.getString("llm.providers.anthropic.base-url")
    override fun getLlmAnthropicModels(): Map<String, String> =
        getLlmModelsHierarchical("anthropic")

    override fun getLlmAnthropicClientConfig(): AnthropicClientConfig {
        val p = "llm.providers.anthropic.settings"
        return AnthropicClientConfig(
            apiVersion = config.tryGetString("$p.api-version") ?: "2023-06-01",
            messagesPath = config.tryGetString("$p.messages") ?: "v1/messages",
            modelsPath = config.tryGetString("$p.models") ?: "v1/models"
        )
    }

    override fun getChoiceProvider(): String = config.getString("llm.choice-provider")

    override fun isLlmCapabilityEnabled(name: String): Boolean =
        isLlmCapabilityEnabled("default", name)

    override fun isLlmCapabilityEnabled(tier: String, name: String): Boolean {
        val tierKey = "llm.capability.$tier.$name"
        return try {
            if (config.hasPath(tierKey)) {
                config.getBoolean(tierKey)
            } else {
                config.getBoolean("llm.capability.$name")
            }
        } catch (_: Exception) {
            true
        }
    }

    override fun getLlmDefaultParams(): Map<String, Map<String, JsonElement>> {
        return try {
            val paramConfig = config.getConfig("llm.param")
            paramConfig.root().keys.associateWith { tier ->
                val t = paramConfig.getConfig(tier)
                t.root().keys.associateWith { key ->
                    t.getValue(key).let { v -> toJsonElement(v.unwrapped()) }
                }
            }
        } catch (e: Exception) {
            log.warn { "Failed to load llm default params: ${e.message}" }
            emptyMap()
        }
    }

    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) ->
            k.toString() to toJsonElement(v)
        })

        is List<*> -> JsonArray(value.map { toJsonElement(it) })
        else -> JsonPrimitive(value.toString())
    }

    override fun getEmbeddingApiKey(): String = config.getString("embedding.api-key")
    override fun getEmbeddingProvider(): String = config.getString("embedding.provider")
    override fun getEmbeddingUrl(): String = config.getString("embedding.url")
    override fun getEmbeddingModel(): String = config.tryGetString("embedding.model") ?: ""

    override fun getSearchApiKey(): String = config.getString("search.api-key")
    override fun getSearchProvider(): String = config.getString("search.provider")
    override fun getSearchUrl(): String = config.getString("search.url")

    override fun getSearchModel(): String = config.tryGetString("search.model") ?: ""

    override fun getVisionApiKey(): String = config.getString("vision.api-key")
    override fun getVisionProvider(): String = config.getString("vision.provider")
    override fun getVisionUrl(): String = config.getString("vision.url")

    override fun getVisionModel(): String = config.tryGetString("vision.model") ?: ""

    override fun getSttApiKey(): String = config.tryGetString("stt.api-key") ?: ""
    override fun getSttProvider(): String = config.tryGetString("stt.provider") ?: "none"
    override fun getSttUrl(): String = config.tryGetString("stt.url") ?: ""
    override fun getSttModel(): String = config.tryGetString("stt.model") ?: ""

    override fun isLlmProxyEnabled(): Boolean =
        if (config.hasPath("llm.proxy")) config.getBoolean("llm.proxy") else true

    override fun getProxyHttp(): String? = config.tryGetString("proxy.http")
    override fun getProxySocks(): String? = config.tryGetString("proxy.socks")

    override fun getOnebotWs(): String = config.getString("onebot.ws")
    override fun getOnebotToken(): String = config.getString("onebot.token")

    override fun getOnebotBots(): Map<String, BotConfig> {
        return try {
            val botsConfig = config.getConfig("onebot.bots")
            val result = mutableMapOf<String, BotConfig>()
            botsConfig.root().keys.forEach { key ->
                val keyStr = key.toString()
                val botConfig = botsConfig.getConfig(keyStr)
                val groups = if (botConfig.hasPath("groups")) {
                    parseGroupsConfig(botConfig.getConfig("groups"))
                } else {
                    emptyMap()
                }
                result[keyStr] = BotConfig(
                    ws = botConfig.getString("ws"),
                    token = botConfig.getString("token"),
                    roleId = botConfig.getString("role-id"),
                    selfId = botConfig.tryGetString("self-id"),
                    groups = groups,
                    groupsOverride = if (botConfig.hasPath("groups-override")) {
                        parseBotGroupsOverride(botConfig.getConfig("groups-override"))
                    } else null,
                    enabledPlugins = if (botConfig.hasPath("enabled-plugins")) {
                        parseStringList(botConfig, "enabled-plugins")
                    } else null,
                    disabledPlugins = if (botConfig.hasPath("disabled-plugins")) {
                        parseStringList(botConfig, "disabled-plugins")
                    } else null,
                    externalHost = botConfig.tryGetString("external-host")
                )
            }
            result
        } catch (e: Exception) {
            log.warn { "Failed to load onebot bots config: ${e.message}" }
            emptyMap()
        }
    }

    private fun parseGroupsConfig(groupsConfig: Config): Map<String, GroupConfig> {
        val result = mutableMapOf<String, GroupConfig>()
        groupsConfig.root().keys.forEach { groupId ->
            val groupIdStr = groupId.toString()
            val admins = try {
                groupsConfig.getStringList("$groupIdStr.admins")
            } catch (_: Exception) {
                emptyList()
            }
            val desire = try {
                groupsConfig.getDouble("$groupIdStr.desire")
            } catch (_: Exception) {
                0.0
            }
            result[groupIdStr] = GroupConfig(admins = admins, desire = desire)
        }
        return result
    }

    private fun parseBotGroupsOverride(ov: Config): BotGroupsOverride {
        val enableGroups = if (ov.hasPath("enable-groups")) {
            try {
                val raw = ov.getString("enable-groups")
                if (raw.isNotBlank()) raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
                else emptyList()
            } catch (_: Exception) {
                try {
                    ov.getStringList("enable-groups").filter { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                }
            }
        } else null

        val messageRedirectMap = if (ov.hasPath("message-redirect-map")) {
            try {
                val raw = ov.getString("message-redirect-map")
                if (raw.isNotBlank()) {
                    raw.split(",").map { it.trim() }
                        .filter { it.contains(":") }
                        .associate { val p = it.split(":"); p[0].trim() to p[1].trim() }
                } else emptyMap()
            } catch (_: Exception) {
                try {
                    val obj = ov.getObject("message-redirect-map")
                    obj.keys.associateWith { key -> ov.getString("message-redirect-map.$key") }
                        .filterValues { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                }
            }
        } else null

        return BotGroupsOverride(
            enableGroups = enableGroups,
            debugGroupId = ov.tryGetString("debug-group-id"),
            messageRedirectMap = messageRedirectMap
        )
    }

    override fun getEffectiveEnableGroups(botKey: String): List<String> =
        (getOnebotBots()[botKey]?.groupsOverride?.enableGroups
            ?: getEnableGroups()) + ChatBridgeConst.MOCK_GROUP_ID.toString()

    override fun getEffectiveDebugGroupId(botKey: String): String? =
        getOnebotBots()[botKey]?.groupsOverride?.debugGroupId ?: getDebugGroupId()

    override fun getEffectiveMessageRedirectMap(botKey: String): Map<String, String> =
        getMessageRedirectMap() + (getOnebotBots()[botKey]?.groupsOverride?.messageRedirectMap ?: emptyMap())

    override fun getAdmins(botConfigKey: String, groupId: String): List<String> {
        val bots = getOnebotBots()
        val botConfig = bots[botConfigKey] ?: return emptyList()
        val groupConfig = botConfig.groups[groupId] ?: return emptyList()
        return groupConfig.admins
    }

    override fun getResourceCleanup(): ResourceCleanupConfig {
        return ResourceCleanupConfig(
            retentionDays = getIntOrDefault("resource-cleanup.retention-days", 15),
            thumbnailRetentionDays = getIntOrDefault("resource-cleanup.thumbnail-retention-days", 30)
        )
    }

    override fun getStateTuning(): StateTuningConfig {
        val d = StateTuningConfig()
        val profileValue = if (config.hasPath("state-tuning.dispatch.profile")) {
            config.getString("state-tuning.dispatch.profile")
        } else {
            null
        }
        val dispatchProfile = StateTriggerProfile.parse(profileValue)
        if (profileValue != null && !StateTriggerProfile.entries.any {
                it.name.equals(profileValue.trim(), ignoreCase = true)
            }
        ) {
            log.warn { "Invalid state trigger profile '$profileValue', falling back to ECONOMY" }
        }
        return StateTuningConfig(
            dispatch = StateDispatchTuningConfig(
                profile = dispatchProfile,
                maxConcurrency = getIntOrDefault("state-tuning.dispatch.max-concurrency", d.dispatch.maxConcurrency)
            ),
            emotion = EmotionTuningConfig(
                emotionRetentionHigh = getDoubleOrDefault(
                    "state-tuning.emotion.emotion-retention-high",
                    d.emotion.emotionRetentionHigh
                ),
                emotionRetentionMedium = getDoubleOrDefault(
                    "state-tuning.emotion.emotion-retention-medium",
                    d.emotion.emotionRetentionMedium
                ),
                emotionRetentionLow = getDoubleOrDefault(
                    "state-tuning.emotion.emotion-retention-low",
                    d.emotion.emotionRetentionLow
                ),
                moodRetention = getDoubleOrDefault("state-tuning.emotion.mood-retention", d.emotion.moodRetention),
                moodEmotionInfluence = getDoubleOrDefault(
                    "state-tuning.emotion.mood-emotion-influence",
                    d.emotion.moodEmotionInfluence
                ),
                emotionHalfLifeSeconds = getLongOrDefault(
                    "state-tuning.emotion.emotion-half-life-seconds",
                    d.emotion.emotionHalfLifeSeconds
                ),
                moodHalfLifeSeconds = getLongOrDefault(
                    "state-tuning.emotion.mood-half-life-seconds",
                    d.emotion.moodHalfLifeSeconds
                )
            ),
            flow = FlowTuningConfig(
                minRatioOfDesire = getDoubleOrDefault("state-tuning.flow.min-ratio-of-desire", d.flow.minRatioOfDesire),
                minValueMin = getDoubleOrDefault("state-tuning.flow.min-value-min", d.flow.minValueMin),
                minValueMax = getDoubleOrDefault("state-tuning.flow.min-value-max", d.flow.minValueMax),
                decayNormalPerMinute = getDoubleOrDefault(
                    "state-tuning.flow.decay-normal-per-minute",
                    d.flow.decayNormalPerMinute
                ),
                decayNegativePerMinute = getDoubleOrDefault(
                    "state-tuning.flow.decay-negative-per-minute",
                    d.flow.decayNegativePerMinute
                ),
                coreInterestBaseCharge = getDoubleOrDefault(
                    "state-tuning.flow.core-interest-base-charge",
                    d.flow.coreInterestBaseCharge
                ),
                continuousInteractionBaseCharge = getDoubleOrDefault(
                    "state-tuning.flow.continuous-interaction-base-charge",
                    d.flow.continuousInteractionBaseCharge
                ),
                deepReplyBaseCharge = getDoubleOrDefault(
                    "state-tuning.flow.deep-reply-base-charge",
                    d.flow.deepReplyBaseCharge
                ),
                groupResonanceBaseCharge = getDoubleOrDefault(
                    "state-tuning.flow.group-resonance-base-charge",
                    d.flow.groupResonanceBaseCharge
                ),
                negativePenalty = getDoubleOrDefault("state-tuning.flow.negative-penalty", d.flow.negativePenalty),
                topicInterruptPenalty = getDoubleOrDefault(
                    "state-tuning.flow.topic-interrupt-penalty",
                    d.flow.topicInterruptPenalty
                ),
                repeatTopicPenalty = getDoubleOrDefault(
                    "state-tuning.flow.repeat-topic-penalty",
                    d.flow.repeatTopicPenalty
                ),
                lowActivityPenalty = getDoubleOrDefault(
                    "state-tuning.flow.low-activity-penalty",
                    d.flow.lowActivityPenalty
                ),
                gettingBetterThreshold = getDoubleOrDefault(
                    "state-tuning.flow.getting-better-threshold",
                    d.flow.gettingBetterThreshold
                ),
                burstThreshold = getDoubleOrDefault("state-tuning.flow.burst-threshold", d.flow.burstThreshold)
            ),
            volition = VolitionTuningConfig(
                baseDesireDefault = getDoubleOrDefault(
                    "state-tuning.volition.base-desire-default",
                    d.volition.baseDesireDefault
                ),
                keywordHitMaxStimulus = getDoubleOrDefault(
                    "state-tuning.volition.keyword-hit-max-stimulus",
                    d.volition.keywordHitMaxStimulus
                ),
                busyGroupStimulus = getDoubleOrDefault(
                    "state-tuning.volition.busy-group-stimulus",
                    d.volition.busyGroupStimulus
                ),
                indirectMentionStimulus = getDoubleOrDefault(
                    "state-tuning.volition.indirect-mention-stimulus",
                    d.volition.indirectMentionStimulus
                ),
                emotionalResonanceStimulus = getDoubleOrDefault(
                    "state-tuning.volition.emotional-resonance-stimulus",
                    d.volition.emotionalResonanceStimulus
                ),
                resetStimulusAmount = getDoubleOrDefault(
                    "state-tuning.volition.reset-stimulus-amount",
                    d.volition.resetStimulusAmount
                ),
                fatigueOnSpeak = getDoubleOrDefault(
                    "state-tuning.volition.fatigue-on-speak",
                    d.volition.fatigueOnSpeak
                ),
                fatigueDecayLowArousal = getDoubleOrDefault(
                    "state-tuning.volition.fatigue-decay-low-arousal",
                    d.volition.fatigueDecayLowArousal
                ),
                fatigueDecayNormal = getDoubleOrDefault(
                    "state-tuning.volition.fatigue-decay-normal",
                    d.volition.fatigueDecayNormal
                ),
                stimulusDecayNormal = getDoubleOrDefault(
                    "state-tuning.volition.stimulus-decay-normal",
                    d.volition.stimulusDecayNormal
                ),
                stimulusDecayHighFlow = getDoubleOrDefault(
                    "state-tuning.volition.stimulus-decay-high-flow",
                    d.volition.stimulusDecayHighFlow
                ),
                normalSpeakThreshold = getDoubleOrDefault(
                    "state-tuning.volition.normal-speak-threshold",
                    d.volition.normalSpeakThreshold
                ),
                highFlowSpeakThreshold = getDoubleOrDefault(
                    "state-tuning.volition.high-flow-speak-threshold",
                    d.volition.highFlowSpeakThreshold
                ),
                highFlowThreshold = getDoubleOrDefault(
                    "state-tuning.volition.high-flow-threshold",
                    d.volition.highFlowThreshold
                ),
                arousalImpulseWeight = getDoubleOrDefault(
                    "state-tuning.volition.arousal-impulse-weight",
                    d.volition.arousalImpulseWeight
                ),
                negativePleasurePenaltyWeight = getDoubleOrDefault(
                    "state-tuning.volition.negative-pleasure-penalty-weight",
                    d.volition.negativePleasurePenaltyWeight
                ),
                flowBonusStart = getDoubleOrDefault(
                    "state-tuning.volition.flow-bonus-start",
                    d.volition.flowBonusStart
                ),
                flowBonusWeight = getDoubleOrDefault(
                    "state-tuning.volition.flow-bonus-weight",
                    d.volition.flowBonusWeight
                )
            ),
            memory = MemoryTuningConfig(
                batchLimit = getIntOrDefault("state-tuning.memory.batch-limit", d.memory.batchLimit),
                minMessages = getIntOrDefault("state-tuning.memory.min-messages", d.memory.minMessages),
                staleRecallDays = getLongOrDefault("state-tuning.memory.stale-recall-days", d.memory.staleRecallDays)
            ),
            summary = SummaryTuningConfig(
                batchLimit = getIntOrDefault("state-tuning.summary.batch-limit", d.summary.batchLimit),
                minMessages = getIntOrDefault("state-tuning.summary.min-messages", d.summary.minMessages),
                retentionDays = getLongOrDefault("state-tuning.summary.retention-days", d.summary.retentionDays)
            ),
            meme = MemeTuningConfig(
                analyzeThreshold = getIntOrDefault("state-tuning.meme.analyze-threshold", d.meme.analyzeThreshold),
                maxContexts = getIntOrDefault("state-tuning.meme.max-contexts", d.meme.maxContexts),
                cleanupDays = getIntOrDefault("state-tuning.meme.cleanup-days", d.meme.cleanupDays),
                lowHeatSeenThreshold = getIntOrDefault(
                    "state-tuning.meme.low-heat-seen-threshold",
                    d.meme.lowHeatSeenThreshold
                )
            ),
            evolution = EvolutionTuningConfig(
                defaultWeight = getIntOrDefault("state-tuning.evolution.default-weight", d.evolution.defaultWeight),
                activeWeightThreshold = getIntOrDefault(
                    "state-tuning.evolution.active-weight-threshold",
                    d.evolution.activeWeightThreshold
                ),
                minWeightThreshold = getIntOrDefault(
                    "state-tuning.evolution.min-weight-threshold",
                    d.evolution.minWeightThreshold
                ),
                increaseOnUse = getIntOrDefault("state-tuning.evolution.increase-on-use", d.evolution.increaseOnUse),
                decayPerCycle = getIntOrDefault("state-tuning.evolution.decay-per-cycle", d.evolution.decayPerCycle),
                decreaseOnNegative = getIntOrDefault(
                    "state-tuning.evolution.decrease-on-negative",
                    d.evolution.decreaseOnNegative
                ),
                staleDays = getIntOrDefault("state-tuning.evolution.stale-days", d.evolution.staleDays),
                recentRangeHours = getLongOrDefault(
                    "state-tuning.evolution.recent-range-hours",
                    d.evolution.recentRangeHours
                ),
                recentMessageLimit = getIntOrDefault(
                    "state-tuning.evolution.recent-message-limit",
                    d.evolution.recentMessageLimit
                ),
                activeLimit = getIntOrDefault("state-tuning.evolution.active-limit", d.evolution.activeLimit)
            )
        )
    }

    private fun getDoubleOrDefault(path: String, default: Double): Double =
        if (config.hasPath(path)) config.getDouble(path) else default

    private fun getIntOrDefault(path: String, default: Int): Int =
        if (config.hasPath(path)) config.getInt(path) else default

    private fun getLongOrDefault(path: String, default: Long): Long =
        if (config.hasPath(path)) config.getLong(path) else default

    override fun getString(path: String): String? =
        if (config.hasPath(path)) config.getString(path) else null

    override fun getDouble(path: String): Double? {
        if (!config.hasPath(path)) return null
        return runCatching { config.getDouble(path) }.getOrNull()
            ?: runCatching { config.getString(path).toDoubleOrNull() }.getOrNull()
    }

    override fun getPlaywrightUrl(): String = config.getString("browser.playwright-url")

    override fun getBrowserDownload(): Boolean = config.getBoolean("browser.download")

    override fun getBrowserExternalHost(): String = config.tryGetString("browser.external-host") ?: "hostmachine"

    override fun getDebugGroupId(): String? = config.tryGetString("groups.debug-group-id")

    override fun getEnableGroups(): List<String> {
        return try {
            val raw = config.getString("groups.enable-groups")
            if (raw.isNotBlank()) {
                raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            try {
                config.getStringList("groups.enable-groups").filter { it.isNotBlank() }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    override fun getMessageRedirectMap(): Map<String, String> {
        return try {
            val raw = config.getString("groups.message-redirect-map")
            if (raw.isNotBlank()) {
                raw.split(",").map { it.trim() }
                    .filter { it.contains(":") }
                    .associate {
                        val parts = it.split(":")
                        parts[0].trim() to parts[1].trim()
                    }
            } else {
                emptyMap()
            }
        } catch (_: Exception) {
            try {
                val obj = config.getObject("groups.message-redirect-map")
                obj.keys.associateWith { key ->
                    config.getString("groups.message-redirect-map.$key")
                }.filterValues { it.isNotBlank() }
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }

    // ===== 插件配置读取方法 =====
    // 优先级（从低到高）：resourcePath < pluginConfigDir < plugin.$pluginId.config（系统属性）
    // 高优先级配置覆盖低优先级配置

    override fun getPluginConfig(pluginClass: KClass<*>, pluginName: String): Config {
        return pluginConfigCache.computeIfAbsent(pluginName) {
            val pluginId = pluginName.substringBefore("_")

            // 1. 从 classpath 加载 resourcePath 作为基础配置
            var config = ConfigFactory.empty()
            val resourcePath = "plugin.json"
            val resourceAsStream = pluginClass.java.classLoader.getResourceAsStream(resourcePath)
            if (resourceAsStream != null) {
                log.info { "Loading base config for $pluginName from classpath: $resourcePath" }
                config = resourceAsStream.use { inputStream ->
                    ConfigFactory.parseReader(inputStream.reader()).resolve(resolveOptions)
                }
            }

            // 2. pluginConfigDir 中的配置覆盖基础配置
            if (pluginConfigDir != null) {
                val pluginConfigFile = File(pluginConfigDir, "$pluginId.json")
                if (pluginConfigFile.exists()) {
                    log.info { "Overriding config for $pluginId from dir: ${pluginConfigFile.absolutePath}" }
                    config = ConfigFactory.parseFile(pluginConfigFile).resolve(resolveOptions).withFallback(config)
                }
            }

            // 3. 系统属性 plugin.$pluginId.config 覆盖其他配置
            val customPath = System.getProperty("plugin.$pluginId.config")
            if (customPath != null) {
                log.info { "Overriding config for $pluginId with: $customPath" }
                config = ConfigFactory.parseFile(File(customPath)).resolve(resolveOptions).withFallback(config)
            }

            config
        }
    }

    private fun parseStringList(cfg: Config, path: String): List<String> {
        return try {
            val raw = cfg.getString(path)
            if (raw.isNotBlank()) raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
            else emptyList()
        } catch (_: Exception) {
            try {
                cfg.getStringList(path).filter { it.isNotBlank() }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    override fun getEnabledPlugins(botKey: String): List<String>? =
        getOnebotBots()[botKey]?.enabledPlugins

    override fun getDisabledPlugins(botKey: String): List<String>? =
        getOnebotBots()[botKey]?.disabledPlugins

    override fun refresh() {
        _config = null
        pluginConfigCache.clear()
        log.info { "Config and plugin config cache cleared" }
    }

    override fun isPluginEnabled(botKey: String, pluginName: String): Boolean {
        // builtin 插件始终启用，忽略 disabled 配置
        if (pluginName.startsWith("builtin_", ignoreCase = true)) {
            return true
        }
        val enabled = getEnabledPlugins(botKey)
        val disabled = getDisabledPlugins(botKey) ?: emptyList()
        val matchShort = { short: String -> pluginName == short || pluginName.startsWith("${short}_") }
        return when {
            enabled != null -> enabled.any(matchShort)
            else -> disabled.none(matchShort)
        }
    }

    override fun getAgentMaxMessageLength(): Int =
        getIntOrDefault("agent.max-message-length", 500)

    override fun getAgentMaxIterations(): Int =
        getIntOrDefault("agent.max-iterations", 50)

}
