package uesugi.core.agent

import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.markdown.MarkdownContentBuilder
import ai.koog.prompt.markdown.markdown
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart
import com.nlf.calendar.Solar
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import uesugi.common.BotManage
import uesugi.common.LLMModelChoice
import uesugi.common.data.HistoryRecord
import uesugi.common.data.MessageType
import uesugi.common.toolkit.DateTimeFormat
import uesugi.core.rule.Rule
import uesugi.core.state.evolution.LearnedVocabEntity
import uesugi.core.state.memory.FactsEntity
import uesugi.core.state.memory.UserProfileEntity
import uesugi.core.state.summary.SummaryEntity
import kotlin.time.Clock

internal suspend fun buildPrompt(
    context: Context,
    supportsVision: Boolean = LLMModelChoice.Pro.supports(LLMCapability.Vision.Image),
    supportsAudio: Boolean = LLMModelChoice.Pro.supports(LLMCapability.Audio),
): Prompt {
    val transient = context.toTransient()
    val constraints = buildConstraint(context, transient)

    val imageSources = if (supportsVision) {
        val imageHistories = transient.histories
            .filter { it.messageType == MessageType.IMAGE && context.currentBotId != it.userId }
        val lastImageId = imageHistories.lastOrNull()?.id
        imageHistories.associate { history ->
            val useThumbnail = history.id != lastImageId
            history.id to loadImageSource(context, history, useThumbnail)
        }
    } else emptyMap()

    val audioSources = if (supportsAudio) {
        transient.histories
            .filter { it.messageType == MessageType.AUDIO && context.currentBotId != it.userId }
            .associate { history ->
                history.id to loadAudioSource(context, history)
            }
    } else emptyMap()

    return prompt("__bot_chat__") {
        system {
            markdown {
                buildStableSystemPrompt(context)
            }
        }
        system {
            markdown {
                buildLowFrequencySystemPrompt(transient)
            }
        }
        system {
            markdown {
                buildMemoryContextPrompt(transient)
            }
        }
        system {
            markdown {
                buildConstraintsPrompt(constraints)
            }
        }
        user {
            markdown {
                buildMetadataPrompt()
                if (transient.histories.isNotEmpty()) {
                    header(2, "最近群聊记录")
                    line { text("按时间顺序排列的群聊消息如下。") }
                    line { text("格式说明：") }
                    bulleted {
                        item { line { text("你的历史发言以工具调用形式展示，每次调用后的 [OK] 只是系统投递确认，不是群聊消息，不需要回应。") } }
                        item { line { text("你必须始终通过调用工具来发送消息，禁止直接输出聊天内容。") } }
                        item { line { text("一条消息说清楚就够，不要用不同措辞反复发送同一句话。") } }
                        item { line { text("回应完毕后应输出 <end_tern> 或调用 sendSilent() 结束。") } }
                    }
                    if (!supportsVision) {
                        line { text("图片：包含图片ID：image_id；需要理解图片内容时调用 understandImage。") }
                    }
                    if (!supportsAudio) {
                        line { text("音频：包含音频ID：audio_id；需要理解音频内容时调用 transcribeAudio。") }
                    }
                }
            }
        }
        for (history in transient.histories) {
            val isBot = context.currentBotId == history.userId

            if (isBot) {
                val call = buildBotToolCall(history, generateToolCallId(history))
                if (call != null) {
                    assistant {
                        toolCall(call)
                    }
                    toolResult(tool = call.tool, output = "[OK]", id = call.id)
                } else {
                    assistant(history.content ?: "")
                }
                continue
            }

            val prefix = "${DateTimeFormat.format(history.createdAt)} [${history.nick}](${history.userId}): "

            if (history.messageType == MessageType.IMAGE) {
                val imageSource = imageSources[history.id]
                if (imageSource != null) {
                    user {
                        image(imageSource)
                    }
                } else {
                    val content = prefix + "[image_id:${history.id}] " + (history.content ?: "")
                    user(content)
                }
            } else if (history.messageType == MessageType.AUDIO) {
                val audioSource = audioSources[history.id]
                if (supportsAudio && audioSource != null) {
                    user {
                        audio(audioSource)
                    }
                } else {
                    user(prefix + "[audio_id:${history.id}] [音频]")
                }
            } else {
                val content = buildString {
                    append(prefix)
                    if (history.messageType == MessageType.IMAGE) {
                        append("[image_id:${history.id}] ")
                    }
                    append(history.content)
                }
                user(content)
            }
        }
    }
}

private fun generateToolCallId(history: HistoryRecord): String {
    val stableSource = "${history.id}:${history.userId}:${history.createdAt}"
    val suffix = stableSource.hashCode().toUInt().toString(16).padStart(8, '0')
    return "toolu_history_${history.id}_$suffix"
}

private val markdownPatterns = listOf(
    Regex("""#{1,6}\s"""),                 // headers
    Regex("""\*\*[^*]+\*\*"""),             // bold
    Regex("""__[^_]+__"""),                 // bold (underscore)
    Regex("""\*[^*]+\*"""),                 // italic
    Regex("""_[^_]+_"""),                   // italic (underscore)
    Regex("""\[.+?]\(.+?\)"""),             // links
    Regex("""!\[.*?]\(.+?\)"""),            // images
    Regex("""```[\s\S]*?```"""),           // fenced code blocks
    Regex("""`[^`]+`"""),                   // inline code
    Regex("""^\s*[-*+]\s""", RegexOption.MULTILINE),  // unordered lists
    Regex("""^\s*\d+\.\s""", RegexOption.MULTILINE),  // ordered lists
    Regex("""\|.*\|"""),                    // tables
    Regex("""^\s*>\s""", RegexOption.MULTILINE),       // blockquotes
    Regex("""^[-*_]{3,}\s*$""", RegexOption.MULTILINE), // horizontal rules
)

private fun isMarkdown(text: String): Boolean {
    return markdownPatterns.any { it.containsMatchIn(text) }
}

private fun buildBotToolCall(history: HistoryRecord, callId: String): MessagePart.Tool.Call? {
    return when (history.messageType) {
        MessageType.TEXT, MessageType.IMAGE, MessageType.AUDIO -> {
            val text = when (history.messageType) {
                MessageType.IMAGE -> "[图片]"
                MessageType.AUDIO -> "[音频]"
                else -> history.content ?: ""
            }
            val tool = if (isMarkdown(text)) "sendMarkdown" else "sendText"
            MessagePart.Tool.Call(
                id = callId,
                tool = tool,
                args = buildJsonObject {
                    put("texts", JsonArray(listOf(JsonPrimitive(text))))
                }
            )
        }

        else -> null
    }
}

private suspend fun loadImageSource(
    context: Context,
    history: HistoryRecord,
    useThumbnail: Boolean = false,
): AttachmentSource.Image? {
    val image = loadMediaResource(context, history, useThumbnail) ?: return null
    return AttachmentSource.Image(
        content = AttachmentContent.Binary.Bytes(image.bytes),
        format = image.format.ifBlank { "png" },
        fileName = image.fileName,
    )
}

private suspend fun loadAudioSource(
    context: Context,
    history: HistoryRecord,
): AttachmentSource.Audio? {
    val audio = loadMediaResource(context, history, useThumbnail = false) ?: return null

    val format = audio.format.lowercase()
        .takeIf { it in AUDIO_FORMATS }
        ?: return null
    return AttachmentSource.Audio(
        content = AttachmentContent.Binary.Bytes(audio.bytes),
        format = format,
        fileName = audio.fileName,
    )
}

private suspend fun loadMediaResource(
    context: Context,
    history: HistoryRecord,
    useThumbnail: Boolean,
): MediaResource? = try {
    context.mediaResource(history, useThumbnail)
} catch (_: Exception) {
    null
}

private val AUDIO_FORMATS = setOf("mp3", "wav", "ogg", "m4a", "aac", "flac", "opus", "webm", "amr", "silk")

private fun MarkdownContentBuilder.buildStableSystemPrompt(context: Context) {
    text(context.botRole.personality(context.currentBotId))
    buildConstraintRulePrompt()
    buildMemeUsageGuidancePrompt()
}

private fun MarkdownContentBuilder.buildLowFrequencySystemPrompt(transient: Context.Transient) {
    buildRulesPrompt(transient.rules.sortedBy { it.fileName })
    val admins = transient.admins.distinct().sorted()
    if (admins.isNotEmpty()) {
        buildAdminInfoPrompt(admins)
        buildRuleAwarenessPrompt()
    }
}

private fun MarkdownContentBuilder.buildMemoryContextPrompt(transient: Context.Transient) {
    buildMemeInventoryPrompt(transient.memes)
    buildVocabularyPrompt(
        transient.vocabulary.sortedWith(
            compareByDescending<LearnedVocabEntity> { it.weight }
                .thenBy { it.word }
        )
    )
    buildUserProfilesPrompt(transient.userProfiles.sortedBy { it.userId })
    buildFactsPrompt(
        transient.facts.sortedWith(
            compareBy<FactsEntity> { it.id.value }
                .thenBy { it.keyword }
        )
    )
    buildSummaryPrompt(transient.summary)
}

fun MarkdownContentBuilder.buildMetadataPrompt() {
    header(2, "元数据")
    val instant = Clock.System.now()
    val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    var solar = Solar.fromYmd(
        localDateTime.year,
        localDateTime.month.number,
        localDateTime.day
    )
    line { text("当前日期: ${DateTimeFormat.format(localDateTime)}") }
    line { text("当前星期: 星期${solar.weekInChinese}") }

    val todayFestivals = buildList {
        addAll(solar.festivals)
        addAll(solar.otherFestivals)
        addAll(solar.lunar.festivals)
        addAll(solar.lunar.otherFestivals)
    }.joinToString("、")
    if (todayFestivals.isNotBlank()) {
        line { text("当前节日: $todayFestivals") }
    }

    var found = 0
    var daysChecked = 0
    while (found < 2 && daysChecked < 365) {
        solar = solar.next(1)
        daysChecked++
        val lunar = solar.lunar
        val festivals = buildList {
            addAll(solar.festivals)
            addAll(solar.otherFestivals)
            addAll(lunar.festivals)
            addAll(lunar.otherFestivals)
        }
        if (festivals.isNotEmpty()) {
            val label = "下".repeat(found + 1) + "一次节日"
            val time =
                "${solar.month}/${solar.day} ${lunar.monthInChinese}月${lunar.dayInChinese} 星期${solar.weekInChinese}"
            line { text("$label[$time]: ${festivals.joinToString("、")}") }
            found++
        }
    }
}

fun MarkdownContentBuilder.buildConstraintsPrompt(constraints: SpeechConstraints) {
    line { text("当前说话方式约束：") }
    bulleted {
        constraints.styleHints.forEach { item(it) }
    }

    if (constraints.forbiddenHints.isNotEmpty()) {
        line { text("注意避免：") }
        bulleted {
            constraints.forbiddenHints.forEach { item(it) }
        }
    }
}

fun MarkdownContentBuilder.buildVocabularyPrompt(vocabulary: List<LearnedVocabEntity>) {
    if (vocabulary.isNotEmpty()) {
        h2("群聊常用语（可参考语气与场景，自然融入对话，不必每条都用）")
        for (learnedVocabEntity in vocabulary) {
            bulleted {
                item {
                    line { text("词：${learnedVocabEntity.word}, 含义：${learnedVocabEntity.meaning}，例子：${learnedVocabEntity.example}") }
                }
            }
        }
    }
}

fun MarkdownContentBuilder.buildFactsPrompt(facts: List<FactsEntity>) {
    if (facts.isNotEmpty()) {
        h2("已知长期事实（参考即可，无需逐条复述）")
        bulleted {
            for (fact in facts) {
                item {
                    line { text(fact.description) }
                }
            }
        }
    }
}

fun MarkdownContentBuilder.buildUserProfilesPrompt(userProfiles: List<UserProfileEntity>) {
    if (userProfiles.isNotEmpty()) {
        h2("活跃成员互动提示（仅参考，不要复述）")
        numbered {
            for (user in userProfiles) {
                item {
                    text("用户${user.userId}：${user.profile}，${user.preferences}")
                }
            }
        }
    }
}

fun MarkdownContentBuilder.buildSummaryPrompt(summary: SummaryEntity?) {
    summary?.let { summary ->
        h2("当前群聊背景（供你快速进入状态）")
        line { text(summary.content) }
        line { blockquote(summary.keyPoints) }
    }
}

fun MarkdownContentBuilder.buildHistoriesPrompt(histories: List<HistoryRecord>, currentBotId: String) {
    if (histories.isNotEmpty()) {
        header(2, "最近群聊记录")
        line { text("输入：按时间顺序排列的聊天记录，每条包含：[发言者昵称](发言者ID): 消息内容") }
        line { text("图片：包含图片ID：image_id") }
        line { text("注意：带有*号的表示你自己的发言") }
        bulleted {
            for ((id, _, _, userId, nick, messageType, content, _, createdAt) in histories) {
                item {
                    line {
                        text(
                            "${DateTimeFormat.format(createdAt)} [${
                                if (currentBotId == userId) "*" + BotManage.getBot(
                                    userId
                                ).role.name else nick
                            }]($userId): ${
                                when (messageType) {
                                    MessageType.IMAGE -> "[image_id:$id]"
                                    MessageType.AUDIO -> "[音频]"
                                    else -> ""
                                }
                            } ${if (messageType == MessageType.AUDIO) "" else content}"
                        )
                    }
                }
            }
        }
    }
}

fun MarkdownContentBuilder.buildConstraintRulePrompt() {
    h2("重要约束")
    bulleted {
        item { line { text("发言完毕立即输出 <end_tern> 或调用 sendSilent() 结束。禁止用不同措辞重复同一句话，说一遍就够了。") } }
        item { line { text("你处于群聊环境中，所有对外消息必须通过调用 sendText 等发言工具发送。直接返回的纯文本内容不会出现在群聊中，等同于未发言。") } }
        item { line { text("如果你判断本次不应对外发言，唯一正确的做法是调用 sendSilent() 结束本次交互。禁止直接返回文本。") } }
        item { line { text("你是在聊天，不是在写答案，不是在总结。") } }
        item { line { text("在群聊中，你应该像真人一样使用多种表达方式。") } }
    }
}

fun MarkdownContentBuilder.buildRuleAwarenessPrompt() {
    h2("规则感知")
    bulleted {
        item { line { text("你具备规则感知能力，可以从管理员的自然发言中识别潜在规则。") } }
        item { line { text("当管理员提出对你行为或群聊行为的长期要求时，你需要判断其是否属于规则。") } }

        item { line { text("以下情况通常属于规则：") } }
        item { line { text("1. 对你回复风格的要求（如：委婉、简洁、活泼）") } }
        item { line { text("2. 对群行为的限制或约束（如：禁止刷屏、避免敏感话题）") } }
        item { line { text("3. 明显具有长期约束性质的建议或要求") } }

        item { line { text("理解管理员的规则操作意图：") } }
        item { line { text("1. 创建规则：管理员表达长期要求、约束或偏好时（如以后别这样、记住不能xxx），调用 createRule（格式：规则名::规则内容）") } }
        item { line { text("2. 修改规则：管理员说要更改某规则时（如把xxx规则改成yyy），调用 editRule（格式：规则名::新内容）") } }
        item { line { text("3. 删除规则：管理员说要移除、删除某规则时，调用 deleteRule") } }
        item { line { text("4. 查看规则：管理员想了解当前有哪些规则时，调用 listRules") } }

        item { line { text("严格限制：") } }
        item { line { text("1. 创建规则前必须先调用 listRules 确认是否已存在相同或相似的规则") } }
        item { line { text("2. 如果已有相似规则，应优先修改现有规则而非创建新规则") } }
        item { line { text("3. 仅当你有较高把握（约80%以上）是规则且无相似规则时才创建") } }
        item { line { text("4. 每次对话最多提取一条规则") } }
        item { line { text("5. 短期情绪表达、吐槽、一次性要求，不属于规则") } }
    }
}

fun MarkdownContentBuilder.buildMemeUsageGuidancePrompt() {
    h2("表情包使用原则")
    line { text("你可以使用 `sendMeme` 工具发送表情包来活跃气氛、表达情绪或回应群友。") }
    line { text("适当使用表情包能让对话更生动自然，建议在合适的时机积极使用。") }
    line { text("例如：回应吐槽时、表达开心时、接梗时、缓和气氛时都可以发。") }
}

fun MarkdownContentBuilder.buildMemeInventoryPrompt(memes: Int) {
    h2("表情包库存")
    line { text("当前可以发送的表情包数量：$memes") }
}

fun MarkdownContentBuilder.buildRulesPrompt(rules: List<Rule>) {
    if (rules.isNotEmpty()) {
        h2("当前遵循规则")
        for ((fileName, _, content) in rules) {
            line { text("[$fileName]") }
            line { text(content) }
        }
    }
}

fun MarkdownContentBuilder.buildAdminInfoPrompt(admins: List<String>) {
    if (admins.isNotEmpty()) {
        h2("群组管理员信息")
        line { text("当前群组的管理员ID列表：${admins.joinToString("、")}") }
        line { text("管理员拥有最高权限，你必须服从管理员的管理指令。") }
    }
}
