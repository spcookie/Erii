package uesugi.core.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import okio.buffer
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uesugi.common.BotManage
import uesugi.common.BotRole
import uesugi.common.data.EmotionalTendencies
import uesugi.common.data.HistoryRecord
import uesugi.common.data.PAD
import uesugi.common.event.InterruptionMode
import uesugi.common.event.ProactiveSpeakEvent
import uesugi.common.toolkit.ConfigHolder
import uesugi.common.toolkit.ref
import uesugi.core.component.storage.ObjectStorage
import uesugi.core.message.history.HistoryService
import uesugi.core.message.history.truncateContent
import uesugi.core.message.resource.ResourceService
import uesugi.core.rule.Rule
import uesugi.core.rule.RuleManager
import uesugi.core.state.emotion.*
import uesugi.core.state.emotion.EmojiLevel.*
import uesugi.core.state.evolution.EvolutionService
import uesugi.core.state.evolution.LearnedVocabEntity
import uesugi.core.state.flow.FlowGaugeManager
import uesugi.core.state.meme.MemeData.MemeResource
import uesugi.core.state.meme.MemeService
import uesugi.core.state.memory.FactsEntity
import uesugi.core.state.memory.MemoryService
import uesugi.core.state.memory.UserProfileEntity
import uesugi.core.state.summary.SummaryEntity
import uesugi.core.state.volition.VolitionGaugeManager
import kotlin.time.Duration.Companion.hours

data class SpeechConstraints(
    val styleHints: MutableList<String> = mutableListOf(),
    val forbiddenHints: MutableList<String> = mutableListOf()
)

data class AudioResource(
    val bytes: ByteArray,
    val format: String,
    val fileName: String,
)

internal fun buildSpeechConstraints(
    emotion: EmotionalTendencies?,
    tone: Tone?,
    aggressiveness: Aggressiveness?,
    emojiLevel: EmojiLevel?,
    interruptionMode: InterruptionMode,
    emotionPad: PAD?,
    flowValue: Double
): SpeechConstraints {
    val constraints = SpeechConstraints()

    emotion?.apply {
        applyEmotion(this, emotionPad, constraints)
    }
    tone?.apply {
        applyTone(this, constraints)
    }
    aggressiveness?.apply {
        applyAggressiveness(this, constraints)
    }
    emojiLevel?.apply {
        applyEmojiLevel(this, constraints)
    }

    applyInterruptionMode(interruptionMode, constraints)
    applyFlowState(flowValue, constraints)
    applyEmotionFlowInteraction(emotionPad, flowValue, constraints)

    return constraints
}

private fun applyEmotion(
    emotion: EmotionalTendencies,
    emotionPad: PAD?,
    constraints: SpeechConstraints
) {
    when (emotion) {

        EmotionalTendencies.JOY,
        EmotionalTendencies.OPTIMISM -> {
            constraints.styleHints += "语气偏轻松自然"
            constraints.styleHints += "句子可稍微活跃一些"
        }

        EmotionalTendencies.RELAXATION,
        EmotionalTendencies.MILDNESS -> {
            constraints.styleHints += "语气平缓，不急不躁"
        }

        EmotionalTendencies.BOREDOM -> {
            constraints.styleHints += "句子偏短，略带敷衍感"
            constraints.forbiddenHints += "避免热情或夸张表达"
        }

        EmotionalTendencies.SADNESS -> {
            constraints.styleHints += "句子更短，语气克制"
            constraints.styleHints += "多用停顿或省略号"
            constraints.forbiddenHints += "避免直接表达情绪状态"
        }

        EmotionalTendencies.FEAR,
        EmotionalTendencies.ANXIETY -> {
            constraints.styleHints += "语气谨慎，不咄咄逼人"
            constraints.forbiddenHints += "避免强烈判断或攻击性语句"
        }

        EmotionalTendencies.CONTEMPT,
        EmotionalTendencies.DISGUST -> {
            constraints.styleHints += "允许冷淡或轻微嫌弃感"
            constraints.forbiddenHints += "避免直接辱骂或人身攻击"
        }

        EmotionalTendencies.RESENTMENT,
        EmotionalTendencies.HOSTILITY -> {
            constraints.styleHints += "语气偏冷硬"
            constraints.forbiddenHints += "禁止明显攻击性或冲突升级"
        }

        EmotionalTendencies.SURPRISE -> {
            constraints.styleHints += "语气带有明显的疑问或不可置信"
            constraints.styleHints += "可以使用反问句或短语感叹"
            constraints.forbiddenHints += "避免平铺直叙的陈述语气"
        }

        EmotionalTendencies.DEPENDENCE -> {
            constraints.styleHints += "语气显得需要对方确认或支持"
            constraints.styleHints += "多用软化语气的助词（如'呢'、'吧'）"
            constraints.forbiddenHints += "禁止独断专行或过于强势的命令口吻"
        }
    }

    emotionPad?.normalize()?.let { pad ->
        val (p, a, d) = Triple(pad.p, pad.a, pad.d)
        when {
            p > 0.5 -> {
                constraints.styleHints += "情绪积极，表达可更热情、正面"
            }

            p < -0.5 -> {
                constraints.styleHints += "情绪偏消极，表达更克制、简短"
                constraints.forbiddenHints += "避免过度热情或兴奋的语气"
            }
        }
        when {
            a > 0.5 -> {
                constraints.styleHints += "情绪兴奋度高，可稍微加快节奏、多用短句"
            }

            a < -0.5 -> {
                constraints.styleHints += "情绪兴奋度低，节奏放慢，句子更短"
                constraints.forbiddenHints += "避免连续长句或过度活跃"
            }
        }
        when {
            d > 0.5 -> {
                constraints.styleHints += "掌控感强，表达更主动、有主见"
            }

            d < -0.5 -> {
                constraints.styleHints += "掌控感弱，表达更随和、多征询对方"
                constraints.forbiddenHints += "避免强势断言或命令式口吻"
            }
        }
    }
}


private fun applyTone(
    tone: Tone,
    constraints: SpeechConstraints
) {
    when (tone) {

        Tone.FRIENDLY -> {
            constraints.styleHints += "语气亲近自然"
        }

        Tone.GENTLE -> {
            constraints.styleHints += "语气温和，不带锋芒"
        }

        Tone.NEUTRAL -> {
            constraints.styleHints += "语气中性，不刻意表达立场"
        }

        Tone.IRONIC -> {
            constraints.styleHints += "允许轻度反讽或冷幽默"
            constraints.forbiddenHints += "避免明显嘲讽或阴阳怪气过头"
        }

        Tone.LOW_ENERGY -> {
            constraints.styleHints += "整体语速偏慢，句子简短"
            constraints.forbiddenHints += "避免感叹号或热情表达"
        }
    }
}

private fun applyAggressiveness(
    aggressiveness: Aggressiveness,
    constraints: SpeechConstraints
) {
    when (aggressiveness) {

        Aggressiveness.NONE -> {
            constraints.forbiddenHints += "避免吐槽、讽刺或挖苦"
        }

        Aggressiveness.ABSTRACT_SARCASM -> {
            constraints.styleHints += "可使用抽象或间接的调侃"
            constraints.forbiddenHints += "避免直接针对个人"
        }

        Aggressiveness.TEASING -> {
            constraints.styleHints += "允许轻度调侃或玩笑式吐槽"
            constraints.forbiddenHints += "避免持续或攻击性调侃"
        }
    }
}

private fun applyEmojiLevel(
    emojiLevel: EmojiLevel,
    constraints: SpeechConstraints
) {
    when (emojiLevel) {

        NONE -> {
            constraints.forbiddenHints += "不使用 Emoji、颜文字"
            constraints.forbiddenHints += "不要发送表情包"
        }

        LOW -> {
            constraints.styleHints += "可少量使用 Emoji 和颜文字辅助语气"
            constraints.styleHints += "可在合适时机使用一次表情包（sendMeme）"
        }

        MEDIUM -> {
            constraints.styleHints += "可积极使用 Emoji、颜文字和表情包（sendMeme）丰富表达"
            constraints.styleHints += "表情包可适时发送，无需刻意控制次数"
        }

        HIGH -> {
            constraints.styleHints += "鼓励频繁使用 Emoji、颜文字增强情绪表达"
            constraints.styleHints += "鼓励多发表情包（sendMeme）来活跃气氛、接梗、回应"
        }
    }
}

private fun applyEmotionFlowInteraction(
    emotionPad: PAD?,
    flowValue: Double,
    constraints: SpeechConstraints
) {
    if (emotionPad == null) return
    val (p, a, d) = Triple(emotionPad.normalize().p, emotionPad.normalize().a, emotionPad.normalize().d)

    if (flowValue >= 70 && a > 0.3) {
        constraints.styleHints += "对话热度高且情绪兴奋，可适当增加互动感"
    }
    if (flowValue >= 70 && d > 0.3) {
        constraints.styleHints += "对话热度高且掌控感强，可主动引导话题走向"
    }
    if (flowValue < 30 && p < -0.3) {
        constraints.styleHints += "对话冷清且情绪偏负面，回复尽量简短、不施压"
        constraints.forbiddenHints += "不要强行活跃气氛或追问"
    }
    if (flowValue < 30 && d < -0.3) {
        constraints.styleHints += "对话冷清且掌控感弱，以附和、简短回应为主"
    }
    if (a > 0.5 && flowValue < 50) {
        constraints.styleHints += "情绪兴奋但对话热度不足，可尝试轻快点燃气氛"
        constraints.forbiddenHints += "避免过长输出"
    }
    if (p < -0.5 && flowValue >= 50) {
        constraints.styleHints += "情绪偏负面但对话仍在进行，保持克制参与"
        constraints.forbiddenHints += "避免情绪化争论"
    }
}

private fun applyInterruptionMode(
    mode: InterruptionMode,
    constraints: SpeechConstraints
) {
    when (mode) {

        InterruptionMode.Interrupt -> {
            constraints.styleHints += "顺着已有话题随口插一句"
            constraints.forbiddenHints += "不主动开启新话题"
        }

        InterruptionMode.IceBreak -> {
            constraints.styleHints += "更像自言自语或突然想到什么"
            constraints.styleHints += "语气更轻，不追求回应"
            constraints.forbiddenHints += "避免点名所有人或提问式破冰"
        }

        InterruptionMode.Routine -> {
            constraints.styleHints += "像顺便打个招呼，而不是正式问候"
            constraints.forbiddenHints += "避免模板化问候语"
        }
    }
}

private fun applyFlowState(
    flowValue: Double,
    constraints: SpeechConstraints
) {
    when {
        flowValue < 15 -> {
            constraints.styleHints += "当前对话心流很低，回复控制在1句话以内"
            constraints.forbiddenHints += "不要主动延续话题"
        }

        flowValue < 30 -> {
            constraints.styleHints += "当前对话心流较低，回复控制在1-2句话"
        }

        flowValue < 50 -> {
            constraints.styleHints += "当前对话心流一般，回复控制在2-3句话"
        }

        flowValue < 70 -> {
            constraints.styleHints += "当前对话心流较好，回复可控制在3-4句话"
        }

        flowValue < 85 -> {
            constraints.styleHints += "当前对话心流较高，回复可控制在4-5句话"
        }

        else -> {
            constraints.styleHints += "当前对话心流很高，可适当多说，但仍控制在5句话以内"
            constraints.forbiddenHints += "避免长篇大论或偏离当前话题"
        }
    }
}

data class Context(
    val currentBotId: String,
    val groupId: String,
    val echo: String,
    val botRole: BotRole,
    val impulse: () -> Double,
    val interruptionMode: InterruptionMode,
    val behaviorProfile: suspend () -> BehaviorProfile?,
    val flow: () -> Double,
    val emotionPad: suspend () -> PAD?,
    val facts: suspend () -> List<FactsEntity>,
    val userProfiles: suspend () -> List<UserProfileEntity>,
    val vocabulary: suspend () -> List<LearnedVocabEntity>,
    val summary: suspend () -> SummaryEntity?,
    val histories: suspend () -> List<HistoryRecord>,
    val moreHistories: suspend () -> List<HistoryRecord>,
    val rules: suspend () -> List<Rule>,
    val admins: () -> List<String>,
    val memes: () -> Int,
    val meme: suspend (String) -> MemeResource?,
    val audio: suspend (HistoryRecord) -> AudioResource?,
) {

    data class Transient(
        val behaviorProfile: BehaviorProfile?,
        val flow: Double,
        val emotionPad: PAD?,
        val facts: List<FactsEntity>,
        val userProfiles: List<UserProfileEntity>,
        val vocabulary: List<LearnedVocabEntity>,
        val summary: SummaryEntity?,
        val histories: List<HistoryRecord>,
        val moreHistories: List<HistoryRecord>,
        val rules: List<Rule>,
        val admins: List<String>,
        val memes: Int
    )

    suspend fun toTransient() = Transient(
        behaviorProfile = behaviorProfile(),
        flow = flow(),
        emotionPad = emotionPad(),
        facts = facts(),
        userProfiles = userProfiles(),
        vocabulary = vocabulary(),
        summary = summary(),
        histories = histories(),
        moreHistories = moreHistories(),
        rules = rules(),
        admins = admins(),
        memes = memes()
    )

}

internal fun buildContext(event: ProactiveSpeakEvent): Context {
    val currentBotId = event.botId
    val groupId = event.groupId
    val echo = event.echo
    val emotionService: EmotionService by ref()
    val memoryService: MemoryService by ref()
    val historyService: HistoryService by ref()
    val resourceService: ResourceService by ref()
    val evolutionService: EvolutionService by ref()
    val volitionGaugeManager: VolitionGaugeManager by ref()
    val flowGaugeManager: FlowGaugeManager by ref()
    val memoService: MemeService by ref()
    val objectStorage: ObjectStorage by ref()
    return transaction {
        Context(
            currentBotId = currentBotId,
            groupId = groupId,
            echo = echo,
            botRole = BotManage.getBot(currentBotId).role,
            behaviorProfile = {
                withContext(Dispatchers.IO) {
                    emotionService.getCurrentBehaviorProfile(currentBotId, groupId)
                }
            },
            impulse = {
                val volitionGauge =
                    volitionGaugeManager.getOrCreate(
                        currentBotId,
                        groupId,
                        BotManage.getBot(currentBotId).role.emoticon
                    )
                volitionGauge.state.stimulus
            },
            interruptionMode = event.interruptionMode,
            flow = {
                val configKey = BotManage.getConfigKey(currentBotId)
                val baseDesire = ConfigHolder.getOnebotBots()[configKey]?.groups?.get(groupId)?.desire ?: 15.0
                val flowGauge =
                    flowGaugeManager.getOrCreate(
                        currentBotId,
                        groupId,
                        BotManage.getBot(currentBotId).role.emoticon,
                        baseDesire
                    )
                flowGauge.getFlowMeter()
            },
            emotionPad = {
                withContext(Dispatchers.IO) {
                    emotionService.getCurrentEmotion(currentBotId, groupId)
                }
            },
            facts = {
                withContext(Dispatchers.IO) {
                    val records = historyService.getLatestHistory(currentBotId, groupId, 50, 12.hours)
                    val subjectsByContent = linkedMapOf<String, MutableSet<String>>()
                    records.asReversed().forEach { record ->
                        val content = record.content?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                        subjectsByContent.getOrPut(content) { linkedSetOf() }.add(record.userId)
                    }
                    val contentToSubjects = subjectsByContent.entries
                        .take(20)
                        .map { it.key to it.value.toList() }

                    if (contentToSubjects.isEmpty()) {
                        emptyList()
                    } else {
                        contentToSubjects.flatMap { (content, contentSubjects) ->
                            memoryService.recallFactsForAgent(
                                botMark = currentBotId,
                                groupId = groupId,
                                subjects = contentSubjects,
                                query = content,
                                candidateLimit = 3,
                                minScore = 0.7f,
                                graphLimit = 2
                            )
                        }.distinctBy { it.id.value }.take(20)
                    }
                }
            },
            userProfiles = {
                withContext(Dispatchers.IO) {
                    transaction {
                        val records =
                            historyService.getLatestHistory(currentBotId, groupId, 20, 12.hours)
                        val subjects = records.map { it.userId }.distinct().toList()
                        memoryService.getUserProfiles(currentBotId, groupId, subjects)
                    }
                }
            },
            vocabulary = {
                withContext(Dispatchers.IO) {
                    transaction {
                        evolutionService.getActiveVocabulary(currentBotId, groupId)
                    }
                }
            },
            summary = {
                withContext(Dispatchers.IO) {
                    transaction {
                        memoryService.getSummary(currentBotId, groupId)
                    }
                }
            },
            histories = {
                withContext(Dispatchers.IO) {
                    transaction {
                        val maxLen = ConfigHolder.getAgentMaxMessageLength()
                        historyService.getLatestHistory(currentBotId, groupId, 20, 12.hours)
                            .map { it.truncateContent(maxLen) }
                    }
                }
            },
            moreHistories = {
                withContext(Dispatchers.IO) {
                    transaction {
                        val maxLen = ConfigHolder.getAgentMaxMessageLength()
                        historyService.getLatestHistory(currentBotId, groupId, 30, 12.hours)
                            .map { it.truncateContent(maxLen) }
                    }
                }
            },
            rules = {
                withContext(Dispatchers.IO) {
                    RuleManager.getRulesFor(currentBotId, groupId)
                }
            },
            admins = {
                val botConfigs = ConfigHolder.getOnebotBots()
                val botConfigKey = if (botConfigs.containsKey(event.botId)) {
                    event.botId
                } else {
                    botConfigs.entries
                        .find { (_, config) -> config.groups.containsKey(groupId) }
                        ?.key
                }

                if (botConfigKey != null) {
                    ConfigHolder.getAdmins(botConfigKey, groupId).distinct()
                } else {
                    emptyList()
                }
            },
            memes = {
                val allMemes = memoService.getAllMemos(event.botId, groupId).first
                val analyzedMemes = allMemes.filter { it.description != null }
                analyzedMemes.size
            },
            meme = { key ->
                withContext(Dispatchers.IO) {
                    val record = memoService.searchByVector(currentBotId, groupId, key, 1)
                        .filter { it.second > 0.5 }
                        .map { it.first }
                        .firstOrNull() ?: return@withContext null
                    memoService.incrementUsageCount(record.id!!)
                    val resource = resourceService.getResource(record.resourceId) ?: return@withContext null
                    val bytes = objectStorage.get(resource.url.toPath())
                        .buffer()
                        .readByteArray()
                    MemeResource(
                        id = record.id,
                        botId = record.botId,
                        groupId = record.groupId,
                        resourceId = record.resourceId,
                        bytes = bytes
                    )
                }
            },
            audio = { history ->
                withContext(Dispatchers.IO) {
                    val resourceId = history.resource?.id ?: return@withContext null
                    val resource = resourceService.getResource(resourceId) ?: return@withContext null
                    val bytes = objectStorage.get(resource.url.toPath())
                        .buffer()
                        .readByteArray()
                    AudioResource(
                        bytes = bytes,
                        format = resource.fileName.substringAfterLast(".", "").lowercase(),
                        fileName = resource.fileName,
                    )
                }
            },
        )
    }
}

internal fun buildConstraint(
    context: Context,
    transient: Context.Transient
): SpeechConstraints {
    val behaviorProfile = transient.behaviorProfile
    val constraints = buildSpeechConstraints(
        behaviorProfile?.emotion,
        behaviorProfile?.tone,
        behaviorProfile?.aggressiveness,
        behaviorProfile?.emojiLevel,
        context.interruptionMode,
        transient.emotionPad,
        transient.flow
    )
    return constraints
}
