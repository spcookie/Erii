package uesugi.plugin

import ai.koog.prompt.executor.model.PromptExecutor
import io.ktor.client.*
import org.koin.core.context.GlobalContext
import org.koin.core.parameter.parametersOf
import org.pf4j.PluginState
import uesugi.LOG
import uesugi.common.BotManage
import uesugi.common.toolkit.ConfigHolder
import uesugi.core.route.CmdRuleRegister
import uesugi.core.route.MetaToolSetRegister
import uesugi.core.route.RouteRuleRegister
import uesugi.plugin.builtin.BuiltinExtension
import uesugi.spi.*

data class PluginRefreshResult(
    val status: String,
    val message: String,
    val requestedPluginId: String? = null,
    val refreshedPlugins: List<String> = emptyList(),
    val loadedExtensions: Int = 0,
    val failedPlugins: Map<String, String> = emptyMap(),
)

data class PluginHelpItem(
    val name: String,
    val description: String,
    val aliases: List<String> = emptyList(),
)

data class PluginHelpCatalog(
    val commands: List<PluginHelpItem>,
    val routes: List<PluginHelpItem>,
)

class PluginLifecycleManager(
    private val pluginManager: AgentPluginManager,
    private val promptExecutor: PromptExecutor,
    private val http: HttpClient,
    private val httpProxy: HttpClient,
) {
    private val lock = Any()
    private val handlesByPlugin = linkedMapOf<String, MutableList<ExtensionHandle>>()

    fun getHelpCatalog(botId: String): PluginHelpCatalog = synchronized(lock) {
        val botConfigKey = BotManage.getConfigKey(botId)
        val enabledExtensions = handlesByPlugin
            .filterKeys { pluginId ->
                pluginId == "builtin" || ConfigHolder.isPluginEnabled(botConfigKey, pluginId)
            }
            .mapValues { (_, handles) -> handles.map { it.extension } }

        buildPluginHelpCatalog(enabledExtensions)
    }

    fun refreshAll(): PluginRefreshResult = synchronized(lock) {
        val failed = linkedMapOf<String, String>()

        closeAllExtensionHandles()
        clearAllRegistries()

        unloadAllPlugins(failed)
        loadAllPlugins(failed)

        val loaded = loadStartedExtensions(failed)
        val refreshedPlugins = handlesByPlugin.keys.toList()
        val status = if (failed.isEmpty()) "ok" else "error"

        LOG.info("Plugin refresh all: plugins=${refreshedPlugins.size}, extensions=$loaded, failed=${failed.size}")

        PluginRefreshResult(
            status = status,
            message = if (failed.isEmpty()) "plugins refreshed" else "plugin refresh completed with failures",
            refreshedPlugins = refreshedPlugins,
            loadedExtensions = loaded,
            failedPlugins = failed,
        )
    }

    fun refreshPlugin(pluginId: String): PluginRefreshResult = synchronized(lock) {
        if (pluginId == "builtin") {
            return@synchronized PluginRefreshResult(
                status = "unsupported",
                message = "builtin extensions can only be refreshed by refreshing all plugins",
                requestedPluginId = pluginId,
                failedPlugins = mapOf(pluginId to "builtin extensions do not have an individual PF4J plugin lifecycle"),
            )
        }

        val wrapper = pluginManager.getPlugin(pluginId)
            ?: return@synchronized PluginRefreshResult(
                status = "not_found",
                message = "plugin not found",
                requestedPluginId = pluginId,
            )

        val pluginPath = wrapper.pluginPath
        val failed = linkedMapOf<String, String>()

        closePlugin(pluginId)

        runCatching {
            val stopped = pluginManager.stopPluginOnly(pluginId)
            if (stopped == PluginState.FAILED) {
                error("failed to stop plugin")
            }
            if (!pluginManager.unloadPluginOnly(pluginId)) {
                error("failed to unload plugin")
            }
            val loadedPluginId = pluginManager.loadPlugin(pluginPath)
            if (loadedPluginId != pluginId) {
                error("plugin id changed after reload: $loadedPluginId")
            }
            val started = pluginManager.startPlugin(pluginId)
            if (!started.isStarted) {
                error("failed to start plugin: $started")
            }
        }.onFailure {
            failed[pluginId] = it.message ?: it::class.simpleName.orEmpty()
        }

        val loaded = if (failed.isEmpty()) loadPluginExtensions(pluginId, failed) else 0
        val status = if (failed.isEmpty()) "ok" else "error"

        LOG.info("Plugin refresh $pluginId: extensions=$loaded, failed=${failed.size}")

        PluginRefreshResult(
            status = status,
            message = if (failed.isEmpty()) "plugin refreshed" else "plugin refresh failed",
            requestedPluginId = pluginId,
            refreshedPlugins = if (failed.isEmpty()) listOf(pluginId) else emptyList(),
            loadedExtensions = loaded,
            failedPlugins = failed,
        )
    }

    fun shutdown() = synchronized(lock) {
        closeAllExtensionHandles()
        clearAllRegistries()
        unloadAllPlugins(mutableMapOf())
    }

    private fun unloadAllPlugins(failed: MutableMap<String, String>) {
        pluginManager.plugins.map { it.pluginId }.forEach { pluginId ->
            runCatching {
                val stopped = pluginManager.stopPluginOnly(pluginId)
                if (stopped == PluginState.FAILED) {
                    error("failed to stop plugin")
                }
                if (!pluginManager.unloadPluginOnly(pluginId)) {
                    error("failed to unload plugin")
                }
            }.onFailure {
                failed[pluginId] = it.message ?: it::class.simpleName.orEmpty()
                LOG.warn("Failed to unload plugin $pluginId", it)
            }
        }
    }

    private fun loadAllPlugins(failed: MutableMap<String, String>) {
        runCatching {
            pluginManager.loadPlugins()
            pluginManager.startPlugins()
        }.onFailure {
            failed["plugin-manager"] = it.message ?: it::class.simpleName.orEmpty()
        }
    }

    private fun loadStartedExtensions(failed: MutableMap<String, String>): Int {
        var loaded = 0

        val builtinExtensions = runCatching {
            pluginManager.getExtensions(BuiltinExtension::class.java)
        }.onFailure {
            failed["builtin"] = it.message ?: it::class.simpleName.orEmpty()
        }.getOrDefault(emptyList())

        if (builtinExtensions.isNotEmpty()) {
            loaded += loadExtensions("builtin", builtinExtensions, failed)
        }

        pluginManager.startedPlugins.forEach { pluginWrapper ->
            loaded += loadPluginExtensions(pluginWrapper.pluginId, failed)
        }

        return loaded
    }

    private fun loadPluginExtensions(pluginId: String, failed: MutableMap<String, String>): Int {
        val extensions = runCatching {
            pluginManager.getExtensions(AgentExtension::class.java, pluginId)
        }.onFailure {
            failed[pluginId] = it.message ?: it::class.simpleName.orEmpty()
        }.getOrDefault(emptyList())

        return loadExtensions(pluginId, extensions, failed)
    }

    private fun loadExtensions(
        pluginId: String,
        extensions: List<AgentExtension<*>>,
        failed: MutableMap<String, String>,
    ): Int {
        var loaded = 0
        extensions.forEach { extension ->
            runCatching {
                val handle = createExtensionHandle(pluginId, extension)
                handlesByPlugin.getOrPut(pluginId) { mutableListOf() }.add(handle)
                ExtensionRegister.add(pluginId, extension)
                registerRoutes(pluginId, extension)
                loaded++
            }.onFailure {
                failed[extension.name] = it.message ?: it::class.simpleName.orEmpty()
                LOG.warn("Failed to load extension ${extension.name}", it)
            }
        }
        return loaded
    }

    private fun createExtensionHandle(pluginId: String, extension: AgentExtension<*>): ExtensionHandle {
        val pluginDef = buildPluginDef(extension)
        val mem = MemImpl()
        val kv = KvImpl(pluginDef)
        val blob = BlobImpl(pluginDef)
        val vector = VectorImpl(pluginDef)
        val config = ConfigImpl(extension)
        val server = ServerImpl(pluginDef)
        val scheduler = GlobalContext.get().get<Scheduler>(parameters = { parametersOf(pluginDef.name) })

        var context: PluginContext? = null
        runCatching {
            val createdContext = PluginContextImpl(
                pluginId,
                extension.name,
                pluginDef,
                mem,
                kv,
                blob,
                vector,
                config,
                scheduler,
                promptExecutor,
                http,
                server,
                httpProxy,
            )
            context = createdContext
            createdContext.apply {
                extension.onLoad(this)
                start()
            }
        }.onFailure {
            PluginCommandExampleRegistry.removeExtension(pluginId, extension.name)
            closeResources(extension, mem, kv, blob, vector, context, server)
            throw it
        }.getOrThrow()

        return ExtensionHandle(pluginId, extension, mem, kv, blob, vector, context, server)
    }

    private fun registerRoutes(pluginId: String, extension: AgentExtension<*>) {
        if (extension is RouteExtension<*>) {
            val (name, description) = extension.matcher
            RouteRuleRegister.addRule(name, description, pluginId)
        }
        if (extension is CmdExtension<*, *, *>) {
            val cmdName = extension.cmd
            CmdRuleRegister.addRule(cmdName, pluginId)
            extension.alias.forEach { alias ->
                CmdRuleRegister.addRule(alias, pluginId, cmdName)
            }
        }
    }

    private fun closePlugin(pluginId: String) {
        val handles = handlesByPlugin.remove(pluginId).orEmpty()
        handles.forEach { it.close() }
        ExtensionRegister.removePlugin(pluginId)
        RouteRuleRegister.removePlugin(pluginId)
        CmdRuleRegister.removePlugin(pluginId)
        MetaToolSetRegister.removePlugin(pluginId)
        PluginCommandExampleRegistry.removePlugin(pluginId)
    }

    private fun closeAllExtensionHandles() {
        handlesByPlugin.keys.toList().forEach(::closePlugin)
        handlesByPlugin.clear()
    }

    private fun clearAllRegistries() {
        ExtensionRegister.clear()
        RouteRuleRegister.clear()
        CmdRuleRegister.clear()
        MetaToolSetRegister.clear()
        PluginCommandExampleRegistry.clear()
    }

    private data class ExtensionHandle(
        val pluginId: String,
        val extension: AgentExtension<*>,
        val mem: MemImpl,
        val kv: KvImpl,
        val blob: BlobImpl,
        val vector: VectorImpl,
        val context: PluginContext?,
        val server: ServerImpl,
    ) {
        fun close() {
            closeResources(extension, mem, kv, blob, vector, context, server)
        }
    }
}

internal fun buildPluginHelpCatalog(
    extensionsByPlugin: Map<String, List<AgentExtension<*>>>,
): PluginHelpCatalog =
    PluginHelpCatalog(
        commands = extensionsByPlugin
            .filterKeys { pluginId -> pluginId != "builtin" }
            .values
            .flatten()
            .filterIsInstance<CmdExtension<*, *, *>>()
            .map { extension ->
                PluginHelpItem(
                    name = extension.cmd,
                    description = extension.description,
                    aliases = extension.alias,
                )
            }
            .distinctBy { it.name }
            .sortedBy { it.name.lowercase() },
        routes = extensionsByPlugin
            .values
            .flatten()
            .filterIsInstance<RouteExtension<*>>()
            .map { extension ->
                PluginHelpItem(
                    name = extension.matcher.first,
                    description = extension.description,
                )
            }
            .distinctBy { it.name }
            .sortedBy { it.name.lowercase() },
    )

private fun closeResources(
    extension: AgentExtension<*>,
    mem: MemImpl,
    kv: KvImpl,
    blob: BlobImpl,
    vector: VectorImpl,
    context: PluginContext?,
    server: ServerImpl,
) {
    runCatching { server.close() }.onFailure {
        LOG.warn("Failed to clear plugin routes for ${extension.name}", it)
    }
    runCatching { context?.close() }.onFailure { LOG.warn("Failed to close plugin context for ${extension.name}", it) }
    runCatching { extension.onUnload() }.onFailure { LOG.warn("Failed to unload extension ${extension.name}", it) }
    runCatching { mem.close() }.onFailure { LOG.warn("Failed to close plugin mem for ${extension.name}", it) }
    runCatching { kv.close() }.onFailure { LOG.warn("Failed to close plugin kv for ${extension.name}", it) }
    runCatching { blob.close() }.onFailure { LOG.warn("Failed to close plugin blob for ${extension.name}", it) }
    runCatching { vector.close() }.onFailure { LOG.warn("Failed to close plugin vector for ${extension.name}", it) }
}
