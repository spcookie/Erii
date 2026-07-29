package uesugi.core.cron

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import org.jobrunr.scheduling.JobScheduler
import uesugi.common.BotManage
import uesugi.common.EventBus
import uesugi.common.event.PSFeature
import uesugi.common.message.CommandUtil
import uesugi.common.toolkit.ConfigHolder
import uesugi.core.component.usage.UsageContext
import uesugi.core.route.CmdRuleRegister
import uesugi.core.route.RouteCalledEvent
import uesugi.core.route.RoutingAgent
import uesugi.onebot.core.message.buildMessage
import uesugi.onebot.sdk.client.api.sendGroupMsg
import uesugi.spi.Feature
import uesugi.spi.sendAgent
import kotlin.time.Duration.Companion.seconds

class CronService(private val jobScheduler: JobScheduler) {

    private val log = KotlinLogging.logger {}

    val store = CronStore()
    val wheel = CronWheel(store)

    companion object {
        private const val SCAN_JOB_ID = "core.cron-scan"
        private const val SCAN_INTERVAL_SECONDS = 30
    }

    fun start() {
        runBlocking { wheel.init() }

        scanDueTasks()

        jobScheduler.scheduleRecurrently(
            SCAN_JOB_ID,
            SCAN_INTERVAL_SECONDS.seconds.toJavaDuration(),
            ::scanDueTasks
        )

        log.info { "CronService started, scanning every $SCAN_INTERVAL_SECONDS seconds" }
    }

    fun stop() {
        log.info { "CronService stopped" }
    }

    fun scanDueTasks() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val now = System.currentTimeMillis()
                for (key in wheel.getRegisteredKeys()) {
                    scanDueTasksForGroup(key, now)
                }
            } catch (e: Exception) {
                log.error(e) { "Error scanning due cron tasks" }
            }
        }
    }

    private suspend fun scanDueTasksForGroup(key: BotGroupKey, now: Long) {
        try {
            val dueTasks = wheel.getAndClearDueTasks(key.botId, key.groupId, now)
            for (task in dueTasks) {
                fireCronTask(task)
            }
        } catch (e: Exception) {
            log.error(e) { "Error scanning tasks for $key" }
        }
    }

    private suspend fun fireCronTask(task: CronTask) {
        try {
            log.info { "Firing cron task ${task.taskId}, type=${task.taskType}" }

            if (task.cronExpression == null) {
                store.deleteTask(task.botId, task.groupId, task.taskId)
            } else {
                val nextTrigger = CronTask.nextTriggerTime(task, System.currentTimeMillis())
                wheel.pushTask(task.copy(triggerTime = nextTrigger, firedAt = System.currentTimeMillis()))
            }

            if (task.groupId in ConfigHolder.getEffectiveEnableGroups(task.botId)) {
                when (task.taskType) {
                    CronTaskType.REMINDER -> sendReminderMessage(task)
                    CronTaskType.TASK_TRIGGER -> fireTaskTrigger(task)
                }
            } else {
                log.warn { "Group ${task.groupId} not enabled for bot ${task.botId}, skipping task ${task.taskId}" }
            }
        } catch (e: Exception) {
            log.error(e) { "Error firing cron task ${task.taskId}" }
        }
    }

    private suspend fun fireTaskTrigger(task: CronTask) {
        when (task.triggerType) {
            TriggerType.ROUTING -> {
                val rule = UsageContext.withUsage(task.botId, task.groupId) {
                    RoutingAgent.route(task.botId, task.groupId, task.content)
                }
                EventBus.postAsync(
                    RouteCalledEvent(
                        botId = task.botId,
                        groupId = task.groupId,
                        senderId = task.senderId ?: "",
                        input = task.content,
                        hit = rule
                    )
                )
                log.info { "Task trigger ROUTING fired: ${task.taskId}, rule=${rule.name}" }
            }

            TriggerType.COMMAND -> {
                val commandName = CommandUtil.parseCommand(task.content)
                if (commandName != null) {
                    val cmd = CmdRuleRegister.getRuleForBot(commandName, task.botId)
                    if (cmd != null) {
                        EventBus.postAsync(
                            RouteCalledEvent(
                                botId = task.botId,
                                groupId = task.groupId,
                                senderId = task.senderId ?: "",
                                input = task.content,
                                hit = cmd
                            )
                        )
                        log.info { "Task trigger COMMAND fired: ${task.taskId}, cmd=$commandName" }
                    } else {
                        log.warn { "Command not found for bot ${task.botId}: $commandName" }
                    }
                } else {
                    log.warn { "Invalid command format in task trigger: ${task.content}" }
                }
            }

            null -> log.warn { "Task trigger has null triggerType for task ${task.taskId}" }
        }
    }

    private fun sendReminderMessage(task: CronTask) {
        val targetMention = task.targetUserId?.let { "@$it " } ?: ""
        sendAgent(
            task.botId,
            task.groupId,
            """
                提醒时间到了，你需要在群里发送消息@用户进行提醒。

                要求：
                1. 语气自然、像人聊天
                2. 简短，不要啰嗦
                3. 不要使用"提醒到了"、"请注意"等正式表达
                4. 不要像系统通知
                5. 可以稍微口语一点，但不要过度发挥

                用户：${targetMention}
                提醒内容：${task.content}
            """.trimIndent(),
            Feature(PSFeature.CHAT_URGENT or PSFeature.GRAB or PSFeature.FALLBACK)
        ) {
            callFallback {
                val msg = buildMessage {
                    task.targetUserId?.let { at(it.toLong()) }
                    text(task.content)
                }
                BotManage.getBot(task.botId)
                    .refBot
                    .sendGroupMsg(task.groupId.toLong(), msg)
            }
            CoroutineScope(Job())
        }
    }
}

private fun kotlin.time.Duration.toJavaDuration(): java.time.Duration =
    java.time.Duration.ofMillis(this.inWholeMilliseconds)
