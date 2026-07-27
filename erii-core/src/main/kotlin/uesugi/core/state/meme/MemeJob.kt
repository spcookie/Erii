package uesugi.core.state.meme

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import okio.buffer
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jobrunr.scheduling.BackgroundJob
import uesugi.common.BotManage
import uesugi.common.data.HistoryTable
import uesugi.common.data.MessageType
import uesugi.common.toolkit.ConfigHolder
import uesugi.common.toolkit.logger
import uesugi.core.component.storage.ObjectStorage
import uesugi.core.component.usage.UsageContext
import uesugi.core.message.history.truncateHistoryContent
import uesugi.core.message.resource.ResourceService
import uesugi.core.state.dispatch.StateWorkKind
import uesugi.core.state.dispatch.StateWorkResult

/**
 * 表情包调度任务
 *
 * 整合了表情包收集和提取两个任务：
 * 2. 提取任务 [doExtracting]: 分析待处理的表情包，提取描述、用途、标签
 *
 * 收集与提取由状态协调器触发，清理仍由 Cron 执行。
 *
 * @property memeService 表情包服务
 * @property resourceService 资源服务
 * @property storage 对象存储
 * @property memeAgent 表情包分析 Agent
 */
class MemeJob(
    private val memeService: MemeService,
    private val resourceService: ResourceService,
    private val storage: ObjectStorage,
    private val memeAgent: MemeAgent,
    private val vectorStoreFactory: MemoVectorStore
) {
    companion object {
        private val log = logger()
    }

    private val mutex = Mutex()

    /**
     * 开启定时触发
     *
     * 启动每天凌晨 3 点执行的清理任务。
     */
    fun openTimingTriggerSignal(rebuildOptions: MemeRebuildOptions = MemeRebuildOptions.from()) {
        if (rebuildOptions.vector) {
            runBlocking {
                if (mutex.tryLock()) {
                    try {
                        log.info("Configured meme vector rebuild started")
                        memeService.rebuildVectorStores()
                    } catch (e: Exception) {
                        log.error("Configured meme vector rebuild failed", e)
                    } finally {
                        mutex.unlock()
                    }
                }
            }
        }

        // 清理任务：每天凌晨 3 点一次
        BackgroundJob.scheduleRecurrently(
            "meme-cleanup-job",
            "0 3 * * *",
            ::doCleanup
        )

        log.info("Meme event processors initialized, cleanup: daily at 03:00")
    }

    /**
     * 处理单个群组的表情包收集
     *
     * @param botId 机器人标识
     * @param groupId 群组ID
     */
    internal suspend fun processGroupCollection(
        botId: String,
        groupId: String,
        batchLimit: Int = 500
    ): StateWorkResult {
        log.debug("开始收集群组表情包, 群组ID: $groupId")

        try {
            // 获取扫描状态（上次扫描到的最后 history id）
            val scanState = withContext(Dispatchers.IO) {
                memeService.getScanState(botId, groupId)
            }
            if (scanState == null) {
                val latestId = withContext(Dispatchers.IO) {
                    memeService.latestHistoryId(botId, groupId)
                } ?: return StateWorkResult(0, 0, false)
                withContext(Dispatchers.IO) {
                    memeService.updateScanState(botId, groupId, latestId)
                }
                return StateWorkResult(0, latestId, false)
            }
            val lastHistoryId = scanState.lastHistoryId
            log.debug("上次扫描到的最后 history id: $lastHistoryId")

            // 获取增量图片消息（只获取 id > lastHistoryId 的）
            val newImages = withContext(Dispatchers.IO) {
                memeService.getRecentImageMessages(
                    botId = botId,
                    groupId = groupId,
                    lastHistoryId = lastHistoryId,
                    limit = batchLimit
                )
            }

            if (newImages.isEmpty()) {
                log.debug("群组 $groupId 没有新的图片消息需要处理")
                return StateWorkResult(0, lastHistoryId, false)
            }

            log.debug("发现 ${newImages.size} 张新图片需要处理")

            var maxHistoryId = lastHistoryId

            for (image in newImages) {
                try {
                    // 更新最大 history id
                    if (image.historyId > maxHistoryId) {
                        maxHistoryId = image.historyId
                    }

                    if (image.md5.isBlank()) {
                        log.warn("Image ${image.resourceId} does not have MD5, skip")
                        continue
                    }

                    // 获取上下文
                    val context = getContextMessage(botId, groupId, image.historyId)

                    // 添加或更新表情包
                    withContext(Dispatchers.IO) {
                        memeService.addOrUpdateMeme(
                            botId = botId,
                            groupId = groupId,
                            resourceId = image.resourceId,
                            md5 = image.md5,
                            context = context
                        )
                    }

                    log.debug("处理图片: md5=${image.md5}, historyId=${image.historyId}")
                } catch (e: Exception) {
                    log.error("Processing image ${image.resourceId} failed", e)
                    throw e
                }
            }

            // 更新扫描状态
            if (maxHistoryId > lastHistoryId) {
                withContext(Dispatchers.IO) {
                    memeService.updateScanState(botId, groupId, maxHistoryId)
                }
                log.debug("扫描状态已更新: lastHistoryId=$maxHistoryId")
            }

            log.debug("群组 $groupId 表情包收集完成")
            return StateWorkResult(
                processedCount = newImages.size,
                cursor = maxHistoryId,
                hasMore = newImages.size == batchLimit,
                wakeKinds = setOf(StateWorkKind.MEME_ANALYZE)
            )

        } catch (e: Exception) {
            log.error("Handling group $groupId meme collection failure", e)
            throw e
        }
    }

    /**
     * 获取指定图片消息前后的文本上下文
     *
     * @param botMark 机器人标识
     * @param groupId 群组ID
     * @param historyId 图片消息的 history id
     * @return 上下文消息文本
     */
    private suspend fun getContextMessage(botMark: String, groupId: String, historyId: Int): String? {
        return withContext(Dispatchers.IO) {
            transaction {
                val maxMessageLength = ConfigHolder.getAgentMaxMessageLength()
            // 获取该图片之前的文本消息（id < historyId 的最近的消息）
            val beforeMessages = HistoryTable
                .select(HistoryTable.content)
                .where {
                    (HistoryTable.botMark eq botMark) and
                            (HistoryTable.groupId eq groupId) and
                            (HistoryTable.messageType eq MessageType.TEXT) and
                            (HistoryTable.id less historyId)
                }
                .orderBy(HistoryTable.id, SortOrder.DESC)
                .limit(10)
                .mapNotNull { it[HistoryTable.content]?.truncateHistoryContent(maxMessageLength) }
                .reversed() // 按时间正序

            // 获取该图片之后的文本消息（id > historyId 的最近的消息）
            val afterMessages = HistoryTable
                .select(HistoryTable.content)
                .where {
                    (HistoryTable.botMark eq botMark) and
                            (HistoryTable.groupId eq groupId) and
                            (HistoryTable.messageType eq MessageType.TEXT) and
                            (HistoryTable.id greater historyId)
                }
                .orderBy(HistoryTable.id, SortOrder.ASC)
                .limit(10)
                .mapNotNull { it[HistoryTable.content]?.truncateHistoryContent(maxMessageLength) }

            // 合并前后消息
            val allContexts = beforeMessages + afterMessages

            allContexts.joinToString("\n").takeIf { it.isNotBlank() }
            }
        }
    }

    /**
     * 执行表情包提取任务
     *
     * 定时分析待处理的表情包：
     * 1. 获取 seenCount >= 3 的未分析表情包
     * 2. 调用 LLM 提取描述、用途、标签
     * 3. 生成向量嵌入
     */
    fun doExtracting() {
        runBlocking {
            if (mutex.tryLock()) {
                try {
                    log.debug("表情包提取任务开始执行")

                    for (botId in BotManage.getAllBotIds()) {
                        val configKey = BotManage.getConfigKey(botId)
                        val groups = ConfigHolder.getEffectiveEnableGroups(configKey)
                        log.debug("需要提取的群组: ${groups.size} 个")
                        for (groupId in groups) {
                            UsageContext.withUsage(botId, groupId) {
                                processGroupExtraction(botId, groupId)
                            }
                        }
                    }

                    log.debug("表情包提取任务执行完成")
                } catch (e: Exception) {
                    log.error("Emoticon extraction task failed", e)
                } finally {
                    mutex.unlock()
                }
            } else {
                log.debug("表情包提取任务正在执行中, 跳过本次调度")
            }
        }
    }

    /**
     * 处理单个群组的表情包提取
     *
     * @param botMark 机器人标识
     * @param groupId 群组ID
     */
    internal suspend fun processGroupExtraction(
        botMark: String,
        groupId: String,
        batchLimit: Int = 20
    ): StateWorkResult {
        log.debug("开始提取群组表情包, 群组ID: $groupId")

        try {
            // 获取待分析的表情包
            val allPendingMemos = withContext(Dispatchers.IO) {
                memeService.getPendingAnalysisMemes(botMark, groupId)
            }
            val pendingMemos = allPendingMemos.take(batchLimit)

            if (pendingMemos.isEmpty()) {
                log.debug("群组 $groupId 没有待分析的表情包")
                return StateWorkResult(0, null, false)
            }

            log.debug("发现 ${pendingMemos.size} 个待分析表情包")

            for (memo in pendingMemos) {
                try {
                    // 记录当前计数（用于更新 lastAnalyzedCount）
                    val currentSeenCount = memo.seenCount

                    // 加载图片资源（用于视觉 LLM 分析 + 向量存储）
                    val resource = runCatching {
                        resourceService.getResource(memo.resourceId)?.apply {
                            bytes = storage.get(this.url.toPath()).buffer().readByteArray()
                        }
                    }.getOrNull()
                    val imageFormat = resource?.fileName?.substringAfterLast(".", "")
                        ?.lowercase()?.takeIf { it.isNotEmpty() }

                    // 调用 LLM 分析
                    val analysis = memeAgent.analyzeMeme(
                        contexts = memo.contexts,
                        imageBytes = resource?.bytes,
                        imageFormat = imageFormat
                    )

                    if (analysis != null) {
                        // 生成向量ID
                        val vectorId = vectorStoreFactory.generateVectorId(botMark, groupId, memo.id!!)

                        // 更新向量存储
                        val updatedMemo = memo.copy(
                            description = analysis.description,
                            purpose = analysis.purpose,
                            tags = analysis.tags.joinToString(","),
                            vectorId = vectorId,
                            lastAnalyzedCount = currentSeenCount,
                            resource = resource
                        )

                        memeService.upsertToVectorStore(updatedMemo)

                        // 更新分析结果（传入当前计数）
                        withContext(Dispatchers.IO) {
                            memeService.updateAnalysis(
                                memeId = memo.id,
                                description = analysis.description,
                                purpose = analysis.purpose,
                                tags = analysis.tags.joinToString(","),
                                vectorId = vectorId,
                                analyzedCount = currentSeenCount
                            )
                        }

                        log.debug("表情包分析完成: memoId=${memo.id}, seenCount=$currentSeenCount, description=${analysis.description}")
                    } else {
                        error("Meme analysis returned no result for memo ${memo.id}")
                    }
                } catch (e: Exception) {
                    log.error("Failed to analyze memes ${memo.id}", e)
                    throw e
                }
            }

            log.debug("群组 $groupId 表情包提取完成")
            return StateWorkResult(pendingMemos.size, null, allPendingMemos.size > pendingMemos.size)

        } catch (e: Exception) {
            log.error("处理群组 $groupId 表情包提取失败", e)
            throw e
        }
    }

    /**
     * 执行表情包清理任务
     *
     * 清理 7 天前未再出现且 seenCount 未达到分析阈值的低热度表情包，
     * 同时清理对应的数据库记录与可能的向量存储数据。
     */
    fun doCleanup() {
        runBlocking {
            if (mutex.tryLock()) {
                try {
                    log.debug("表情包清理任务开始执行")
                    val count = memeService.cleanupLowHeatMemes(
                        daysAgo = ConfigHolder.getStateTuning().meme.cleanupDays
                    )
                    log.info("Completed the emoticon cleanup task: cleaned up {} low-popularity emoticons", count)
                } catch (e: Exception) {
                    log.error("Emoticon cleanup task failed", e)
                } finally {
                    mutex.unlock()
                }
            } else {
                log.debug("表情包任务正在执行中, 跳过本次清理调度")
            }
        }
    }
}
