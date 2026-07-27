package uesugi.core.state.evolution

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uesugi.common.data.HistoryEntity
import uesugi.common.data.HistoryTable
import uesugi.common.data.MessageType
import uesugi.common.toolkit.ConfigHolder
import uesugi.common.toolkit.logger
import uesugi.core.manage.ManageListQuery
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

/**
 * 词汇库服务 - 管理学习到的流行语、梗、黑话
 *
 * 提供增删改查和热度管理功能，实现"遗忘机制"
 */
class EvolutionService {
    companion object {
        private val log = logger()

        private const val MIN_MESSAGE_LENGTH = 2
    }

    /**
     * 获取6小时前最活跃的消息
     *
     * @param botMark 机器人标识
     * @param groupId 群组ID
     * @param limit 最大消息数量，默认500条
     * @return 消息内容列表
     */
    @OptIn(ExperimentalTime::class)
    fun getMostActiveMessages(
        botMark: String,
        groupId: String,
        limit: Int = 500,
        range: Duration
    ): List<String> = transaction {
        val yesterday = Clock.System.now().minus(range).toLocalDateTime(TimeZone.currentSystemDefault())

        log.debug("开始获取${range.inWholeHours}小时前活跃消息, botId=$botMark, groupId=$groupId, limit=$limit")

        val messages = HistoryEntity.find {
            (HistoryTable.botMark eq botMark) and
                    (HistoryTable.groupId eq groupId) and
                    (HistoryTable.createdAt greaterEq yesterday) and
                    (HistoryTable.messageType eq MessageType.TEXT) and
                    (HistoryTable.userId neq botMark)
        }
            .orderBy(HistoryTable.id to SortOrder.DESC)
            .limit(limit)
            .toList()
            .reversed()
            .mapNotNull { entity ->
                val content = entity.content ?: return@mapNotNull null
                if (shouldFilterMessage(content)) null else content
            }

        log.debug("获取6小时前活跃消息完成, botId=$botMark, groupId=$groupId, 消息数=${messages.size}")
        messages
    }

    /**
     * 判断消息是否应该被过滤掉
     *
     * 清洗规则：
     * 1. 剔除 URL、纯表情包、系统消息
     * 2. 剔除过短的无意义字符（如 "a", "1"）
     *
     * @param content 消息内容
     * @return true表示应该过滤，false表示保留
     */
    private fun shouldFilterMessage(content: String): Boolean {
        return content.length < MIN_MESSAGE_LENGTH ||
                isUrl(content) ||
                isPureEmoji(content)
    }

    fun filterMessages(messages: List<String>): List<String> =
        messages.filterNot(::shouldFilterMessage)

    /**
     * 判断是否为URL
     */
    private fun isUrl(content: String): Boolean {
        return content.startsWith("http://") || content.startsWith("https://")
    }

    /**
     * 判断是否为纯表情包
     *
     * 检测Unicode表情符号范围
     */
    private fun isPureEmoji(content: String): Boolean {
        return content.all { char ->
            char.code in 0x1F300..0x1F9FF ||
                    char.code in 0x2600..0x26FF ||
                    char.code in 0x2700..0x27BF
        }
    }

    @OptIn(ExperimentalTime::class)
    fun addOrUpdateWord(
        botMark: String,
        groupId: String,
        slangWord: SlangWord,
        weight: Int? = null
    ): LearnedVocabRecord = transaction {
        val existing = LearnedVocabEntity.find {
            (LearnedVocabTable.botMark eq botMark) and
                    (LearnedVocabTable.groupId eq groupId) and
                    (LearnedVocabTable.word eq slangWord.word)
        }.firstOrNull()

        val tuning = ConfigHolder.getStateTuning().evolution
        val entity = if (existing != null) {
            existing.apply {
                type = slangWord.type
                meaning = slangWord.meaning
                example = slangWord.example
                this.weight = weight?.coerceIn(0, 100) ?: ((this.weight + tuning.increaseOnUse).coerceAtMost(100))
                lastSeen = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            }
            log.debug("更新词汇成功, word=${slangWord.word}, newWeight=${existing.weight}, groupId=$groupId")
            existing
        } else {
            LearnedVocabEntity.new {
                this.botMark = botMark
                this.groupId = groupId
                word = slangWord.word
                type = slangWord.type
                meaning = slangWord.meaning
                example = slangWord.example
                this.weight = weight?.coerceIn(0, 100) ?: tuning.defaultWeight
                lastSeen = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            }
        }
        entity.toRecord()
    }

    fun getVocabularyById(id: Int): LearnedVocabRecord? = transaction {
        LearnedVocabEntity.findById(id)?.toRecord()
    }

    /**
     * 获取活跃词汇（热度 >= 50）
     *
     * 用于生成 Prompt 时注入的语言风格指南
     *
     * @param botMark 机器人标识
     * @param groupId 群组ID
     * @return 活跃词汇列表，按热度降序排列
     */
    fun getActiveVocabulary(
        botMark: String,
        groupId: String,
        limit: Int = -1
    ): List<LearnedVocabEntity> = transaction {
        val tuning = ConfigHolder.getStateTuning().evolution
        val effectiveLimit = if (limit > 0) limit else tuning.activeLimit
        val vocabs = LearnedVocabEntity.find {
            (LearnedVocabTable.botMark eq botMark) and
                    (LearnedVocabTable.groupId eq groupId) and
                    (LearnedVocabTable.weight greaterEq tuning.activeWeightThreshold)
        }
            .orderBy(LearnedVocabTable.weight to SortOrder.DESC)
            .limit(effectiveLimit)
            .toList()

        log.debug("获取活跃词汇, groupId=$groupId, 数量=${vocabs.size}")
        vocabs
    }

    /**
     * 获取所有词汇（包括低热度）
     *
     * @param botMark 机器人标识
     * @param groupId 群组ID
     * @return 所有词汇列表
     */
    fun getAllVocabulary(
        botMark: String,
        groupId: String,
        offset: Int = 0,
        limit: Int = 0
    ): Pair<List<LearnedVocabEntity>, Int> = getAllVocabulary(
        botMark,
        groupId,
        ManageListQuery(offset = offset, limit = limit)
    )

    fun getAllVocabulary(
        botMark: String,
        groupId: String,
        listQuery: ManageListQuery
    ): Pair<List<LearnedVocabEntity>, Int> = transaction {
        var condition: Op<Boolean> =
            (LearnedVocabTable.botMark eq botMark) and
                    (LearnedVocabTable.groupId eq groupId)
        if (listQuery.search.isNotBlank()) {
            condition = condition and (
                    (LearnedVocabTable.word.lowerCase() like listQuery.searchPattern) or
                            (LearnedVocabTable.type.lowerCase() like listQuery.searchPattern) or
                            (LearnedVocabTable.meaning.lowerCase() like listQuery.searchPattern) or
                            (LearnedVocabTable.example.lowerCase() like listQuery.searchPattern)
                    )
        }
        val total = LearnedVocabEntity.find { condition }.count().toInt()
        val query = LearnedVocabTable
            .selectAll()
            .where { condition }
        when (listQuery.sortBy) {
            "id" -> query.orderBy(LearnedVocabTable.id to listQuery.sortOrder)
            "word" -> query.orderBy(LearnedVocabTable.word to listQuery.sortOrder, LearnedVocabTable.id to listQuery.sortOrder)
            "weight" -> query.orderBy(LearnedVocabTable.weight to listQuery.sortOrder, LearnedVocabTable.id to listQuery.sortOrder)
            else -> query.orderBy(
                LearnedVocabTable.weight to SortOrder.DESC,
                LearnedVocabTable.lastSeen to SortOrder.DESC,
                LearnedVocabTable.id to SortOrder.DESC
            )
        }
        val pageQuery = if (listQuery.limit > 0) {
            query.limit(listQuery.limit).offset(listQuery.offset.toLong())
        } else {
            query.offset(listQuery.offset.toLong())
        }
        val items = LearnedVocabEntity.wrapRows(pageQuery).toList()

        log.debug("获取所有词汇, groupId=$groupId, 数量=${items.size}")
        items to total
    }

    /**
     * 热度衰减机制 - 遗忘过气的梗
     *
     * 规则：
     * 1. 如果词汇在最近3天的聊天记录中出现，热度+10
     * 2. 如果词汇在最近3天没有出现，热度-10
     * 3. 当热度 < 20 时，从词汇库中删除（遗忘机制）
     *
     * @param botMark 机器人标识
     * @param groupId 群组ID
     * @param recentMessages 最近的消息列表，用于检测词汇是否仍在使用
     */
    @OptIn(ExperimentalTime::class)
    fun decayOldWords(
        botMark: String,
        groupId: String,
        recentMessages: List<String>
    ) = transaction {
        val tuning = ConfigHolder.getStateTuning().evolution
        val staleCutoff =
            Clock.System.now().minus(tuning.staleDays.days).toLocalDateTime(TimeZone.currentSystemDefault())

        log.debug("开始执行词汇热度衰减, groupId=$groupId")

        var decayedCount = 0
        var deletedCount = 0
        var reinforcedCount = 0

        LearnedVocabEntity.find {
            (LearnedVocabTable.botMark eq botMark) and
                    (LearnedVocabTable.groupId eq groupId)
        }.forEach { vocab ->
            val wordUsedRecently = recentMessages.any { it.contains(vocab.word, ignoreCase = true) }

            if (wordUsedRecently) {
                vocab.weight = (vocab.weight + tuning.increaseOnUse).coerceAtMost(100)
                vocab.lastSeen = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                reinforcedCount++
                log.debug("词汇仍在使用，热度增加, word=${vocab.word}, newWeight=${vocab.weight}")
            } else if (vocab.lastSeen < staleCutoff) {
                val oldWeight = vocab.weight
                vocab.weight = (vocab.weight - tuning.decayPerCycle).coerceAtLeast(0)
                decayedCount++
                log.debug("词汇未使用，热度衰减, word=${vocab.word}, oldWeight=$oldWeight, newWeight=${vocab.weight}")
            }

            if (vocab.weight < tuning.minWeightThreshold) {
                log.debug("词汇热度过低，执行遗忘, word=${vocab.word}, weight=${vocab.weight}")
                vocab.delete()
                deletedCount++
            }
        }

        log.debug("词汇热度衰减完成, groupId=$groupId, 衰减=$decayedCount, 强化=$reinforcedCount, 遗忘=$deletedCount")
    }

    /**
     * 增加词汇热度（用于强化学习）
     *
     * 场景：如果机器人用了梗，且用户回复"哈哈哈"或点赞，该梗热度 +10
     *
     * @param botMark 机器人标识
     * @param groupId 群组ID
     * @param word 词汇
     */
    fun increaseWeight(
        botMark: String,
        groupId: String,
        word: String
    ) = transaction {
        val tuning = ConfigHolder.getStateTuning().evolution
        LearnedVocabEntity.find {
            (LearnedVocabTable.botMark eq botMark) and
                    (LearnedVocabTable.groupId eq groupId) and
                    (LearnedVocabTable.word eq word)
        }.firstOrNull()?.apply {
            weight = (weight + tuning.increaseOnUse).coerceAtMost(100)
            log.debug("增加词汇热度（正向反馈）, word=$word, newWeight=$weight, groupId=$groupId")
        }
    }

    fun updateWordById(
        id: Int,
        word: String,
        type: String,
        meaning: String,
        example: String,
        weight: Int
    ): LearnedVocabRecord? = transaction {
        LearnedVocabEntity.findById(id)?.apply {
            this.word = word
            this.type = type
            this.meaning = meaning
            this.example = example
            this.weight = weight.coerceIn(0, 100)
            log.debug("更新词汇成功, id=$id, word=$word")
        }?.toRecord()
    }

    fun deleteWordById(id: Int, botMark: String, groupId: String): Boolean = transaction {
        val vocab = LearnedVocabEntity.findById(id)?.takeIf { it.botMark == botMark && it.groupId == groupId }
        vocab?.delete()
        vocab != null
    }

    /**
     * 降低词汇热度（用于负面反馈）
     *
     * 场景：如果机器人用了梗，用户回复"你有病吧"或"看不懂"，该梗热度 -50（立即止损）
     *
     * @param botMark 机器人标识
     * @param groupId 群组ID
     * @param word 词汇
     */
    fun decreaseWeight(
        botMark: String,
        groupId: String,
        word: String
    ) = transaction {
        val tuning = ConfigHolder.getStateTuning().evolution
        LearnedVocabEntity.find {
            (LearnedVocabTable.botMark eq botMark) and
                    (LearnedVocabTable.groupId eq groupId) and
                    (LearnedVocabTable.word eq word)
        }.firstOrNull()?.apply {
            weight = (weight - tuning.decreaseOnNegative).coerceAtLeast(0)
            log.debug("降低词汇热度（负面反馈）, word=$word, newWeight=$weight, groupId=$groupId")

            if (weight < tuning.minWeightThreshold) {
                log.debug("词汇因负面反馈被遗忘, word=$word, groupId=$groupId")
                delete()
            }
        }
    }
}
