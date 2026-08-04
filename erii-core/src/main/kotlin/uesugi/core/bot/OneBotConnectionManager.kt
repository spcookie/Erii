package uesugi.core.bot

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uesugi.LOG
import uesugi.common.BotManage
import uesugi.common.EventBus
import uesugi.common.event.integration.BotConnectedEvent
import uesugi.common.toolkit.BotConfig
import uesugi.common.toolkit.ConfigHolder
import uesugi.core.MessageEventListener
import uesugi.core.bot.OneBotConnectionManager.connectOne
import uesugi.core.bot.OneBotConnectionManager.connections
import uesugi.core.bot.OneBotConnectionManager.mutex
import uesugi.onebot.core.config.OneBotConfig
import uesugi.onebot.core.pipeline.LoggingMiddleware
import uesugi.onebot.sdk.client.OneBotClient
import uesugi.onebot.sdk.client.api.getLoginInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * 统一管理 OneBot 连接的生命周期(建立、断开、按配置 diff 刷新)。
 *
 * 只跟踪自己创建的连接(见 [connections]),不触碰 [BotManage] 中由其它途径注册的 bot
 * (例如 ChatBridge 的 mock bot),因此 refresh 的 diff 不会误删它们。
 */
object OneBotConnectionManager {

    private data class Connection(
        val configKey: String,
        val selfId: String,
        val client: OneBotClient,
        val listener: MessageEventListener,
        // 连接时的配置快照,用于 refresh diff 判断变更类型
        val ws: String,
        val token: String,
        val roleId: String,
    )

    data class RefreshResult(
        val added: List<String> = emptyList(),
        val removed: List<String> = emptyList(),
        val reconnected: List<String> = emptyList(),
        val roleUpdated: List<String> = emptyList(),
        val failed: List<String> = emptyList(),
    )

    private val connections = ConcurrentHashMap<String, Connection>()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("onebot-conn-mgr"))

    @Volatile
    private var startJob: Job? = null

    /** 启动时的初次连接,非阻塞。 */
    fun start() {
        startJob = scope.launch { mutex.withLock { connectAll() } }
    }

    private suspend fun connectAll() {
        val botConfigs = ConfigHolder.getOnebotBots()
        if (botConfigs.isEmpty()) {
            LOG.warn("No robots configured")
            return
        }
        LOG.info("Prepare to connect ${botConfigs.size} robots")
        botConfigs.forEach { (key, config) -> connectOne(key, config) }
    }

    /**
     * 建立单个连接。假定已持有 [mutex]。
     *
     * 幂等:已在 [connections] 中则跳过。原子:任何失败或取消都会完整回滚已建立的
     * client / [BotManage] 注册 / listener,不留孤儿状态;成功时才写入 [connections]。
     */
    private suspend fun connectOne(configKey: String, config: BotConfig) {
        if (connections.containsKey(configKey)) {
            LOG.warn("Robot $configKey already connected, skip")
            return
        }
        val role = BotRoleManager.getRole(config.roleId) ?: BotRoleManager.getDefaultRole()
        LOG.info("Connecting robot $configKey, using role: ${role.name}")

        var client: OneBotClient? = null
        var listener: MessageEventListener? = null
        var registered = false
        try {
            val onebotConfig = OneBotConfig(
                wsForwardClientEnable = true,
                wsForwardClientUseUniversal = true,
                wsForwardClientUrl = config.ws,
                accessToken = config.token
            )
            client = OneBotClient(onebotConfig).also {
                it.use(LoggingMiddleware(LOG))
                it.start()
            }

            val selfId = resolveSelfId(client, configKey, config) ?: run {
                LOG.error("Robot $configKey: get_login_info failed and config.selfId is not set, skipping")
                runCatching { client.stop() }
                return
            }

            BotManage.registerBot(configKey, client, selfId, role)
            registered = true

            EventBus.postAsync(
                BotConnectedEvent(
                    botId = selfId,
                    configKey = configKey,
                    roleName = role.name
                )
            )

            listener = MessageEventListener(selfId, configKey, role.name).also {
                it.register(client)
            }

            connections[configKey] = Connection(
                configKey = configKey,
                selfId = selfId,
                client = client,
                listener = listener,
                ws = config.ws,
                token = config.token,
                roleId = config.roleId,
            )

            LOG.info("Robot $configKey (${role.name}) has been connected: $selfId")
        } catch (e: CancellationException) {
            rollbackPartialConnect(configKey, client, listener, registered)
            throw e
        } catch (e: Exception) {
            rollbackPartialConnect(configKey, client, listener, registered)
            LOG.error("Robot $configKey, failed to connect: ${e.message}")
        }
    }

    /** 解析 selfId:优先 get_login_info,失败回退 config.selfId;取消异常照常向上传播。 */
    private suspend fun resolveSelfId(client: OneBotClient, configKey: String, config: BotConfig): String? =
        try {
            client.getLoginInfo().userId.toString().also {
                LOG.info("Robot $configKey selfId resolved via get_login_info: $it")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            config.selfId
        }

    /** 回滚 [connectOne] 中途已建立的部分状态,避免留下孤儿连接。 */
    private suspend fun rollbackPartialConnect(
        configKey: String,
        client: OneBotClient?,
        listener: MessageEventListener?,
        registered: Boolean,
    ) {
        connections.remove(configKey)
        listener?.let { runCatching { it.close() } }
        if (registered) runCatching { BotManage.removeBot(configKey) }
        client?.let { runCatching { it.stop() } }
    }

    /** 断开并清理单个连接。假定已持有 [mutex]。 */
    private suspend fun disconnectOne(configKey: String) {
        val conn = connections.remove(configKey) ?: return
        conn.listener.close()
        try {
            conn.client.stop()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Error stopping bot ${conn.selfId}: ${e.message}")
        }
        BotManage.removeBot(configKey)
        LOG.info("Robot $configKey (${conn.selfId}) disconnected")
    }

    /**
     * 按当前配置 diff 已管理的连接:新增→连接,移除→断开,ws/token 变→重连,仅 roleId 变→热刷新角色。
     */
    suspend fun refresh(): RefreshResult = mutex.withLock {
        val desired = ConfigHolder.getOnebotBots()
        val current = connections.keys.toSet()

        val removed = mutableListOf<String>()
        val added = mutableListOf<String>()
        val reconnected = mutableListOf<String>()
        val roleUpdated = mutableListOf<String>()
        val failed = mutableListOf<String>()

        // 1. 移除:配置里已没有的连接
        for (key in current - desired.keys) {
            try {
                disconnectOne(key)
                removed += key
            } catch (e: Exception) {
                LOG.error("Failed to disconnect $key: ${e.message}")
                failed += key
            }
        }

        // 2. 新增:配置里新增的 bot
        for (key in desired.keys - current) {
            try {
                connectOne(key, desired.getValue(key))
                if (connections.containsKey(key)) added += key else failed += key
            } catch (e: Exception) {
                LOG.error("Failed to connect $key: ${e.message}")
                failed += key
            }
        }

        // 3. 共有:比较快照判断变更类型
        for (key in desired.keys intersect current) {
            val conn = connections.getValue(key)
            val cfg = desired.getValue(key)
            try {
                when {
                    conn.ws != cfg.ws || conn.token != cfg.token -> {
                        disconnectOne(key)
                        connectOne(key, cfg)
                        if (connections.containsKey(key)) reconnected += key else failed += key
                    }

                    conn.roleId != cfg.roleId -> {
                        val role = BotRoleManager.getRole(cfg.roleId) ?: BotRoleManager.getDefaultRole()
                        BotManage.refreshBotRole(key, role)
                        connections[key] = conn.copy(roleId = cfg.roleId)
                        roleUpdated += key
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to refresh $key: ${e.message}")
                failed += key
            }
        }

        LOG.info("Bot connections refreshed: added=$added removed=$removed reconnected=$reconnected roleUpdated=$roleUpdated failed=$failed")
        RefreshResult(added, removed, reconnected, roleUpdated, failed)
    }

    /** 断开所有连接并取消管理器协程。 */
    suspend fun disconnectAll() {
        // 先取消并等待初次连接任务结束,确保它不会在拆除之后又建立连接。
        startJob?.cancelAndJoin()
        mutex.withLock {
            connections.keys.toList().forEach { disconnectOne(it) }
        }
        scope.cancel()
    }
}
