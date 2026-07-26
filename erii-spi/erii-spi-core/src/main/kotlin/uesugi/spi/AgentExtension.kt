package uesugi.spi


import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.serialization.JSONElement
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.PrintRequest
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalInfo
import com.github.ajalt.mordant.terminal.TerminalInterface
import com.typesafe.config.Config
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.http.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okio.Path
import org.jetbrains.exposed.v1.jdbc.Query
import org.pf4j.ExtensionPoint
import org.pf4j.Plugin
import org.pf4j.PluginWrapper
import uesugi.common.ChatToolSet
import uesugi.common.IBotManage
import uesugi.common.IntegrationEvent
import uesugi.common.data.HistoryRecord
import uesugi.common.event.PSFeature
import uesugi.common.event.ProactiveSpeakFeature
import java.io.InputStream
import java.util.*
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass
import kotlin.reflect.full.allSupertypes
import kotlin.reflect.full.createInstance
import kotlin.time.Duration
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid


private val PLUGIN_CONTEXT_HOLDER = mutableMapOf<KClass<*>, PluginWrapper>()

private val REF_CACHE = mutableMapOf<KClass<*>, KClass<*>>()

abstract class AgentPlugin : Plugin() {

    fun setWrapper(wrapper: PluginWrapper) {
        PLUGIN_CONTEXT_HOLDER[this::class] = wrapper
    }

}

interface AgentExtension<T : AgentPlugin> : ExtensionPoint {

    val name: String
        get() = (PLUGIN_CONTEXT_HOLDER[ref]?.let { it.pluginId + "_" } ?: "") + this::class.simpleName!!

    fun onLoad(context: PluginContext)

    fun onUnload() {}

    @Suppress("UNCHECKED_CAST")
    val ref: KClass<T>
        get() {
            return REF_CACHE.getOrPut(this::class) {
                this::class.allSupertypes
                    .firstOrNull { it.classifier == AgentExtension::class }
                    ?.arguments
                    ?.firstOrNull()
                    ?.type
                    ?.classifier as? KClass<*>
                    ?: error("Cannot determine generic type for AgentExtension: ${this::class}")
            } as KClass<T>
        }

}

interface CmdExtension<Context, Arg : ArgParserHolder<Context>, Plugin : AgentPlugin> : AgentExtension<Plugin> {
    val cmd: String
    val alias: List<String>
        get() = emptyList()

    fun Meta.parser(context: Context): Arg {
        val holder = getHolder(this@parser)
        holder.init(this@parser, context)
        val withoutSlash = input!!.removePrefix("/")
        val matched = (listOf(cmd) + alias).firstOrNull { withoutSlash.startsWith(it) } ?: cmd
        val args = buildList {
            addAll(
                withoutSlash.removePrefix(matched)
                    .split(" ")
                    .filter { it.isNotBlank() }
                    .map { it.trim() }
            )
        }
        holder.main(args)
        return holder
    }

    @Suppress("UNCHECKED_CAST")
    private fun getHolder(meta: Meta): Arg {
        val cmdSuperType = this@CmdExtension::class.supertypes.first { it.classifier == CmdExtension::class }
        val typeArg = cmdSuperType.arguments[1].type
        val kClass = typeArg?.classifier as? KClass<*> ?: error("Not a class")
        val holder = kClass.createInstance()
        return holder as Arg
    }
}

class SlashHolder : ArgParserHolder<Unit>() {
    val args: List<String> by argument().multiple()
    override fun run() {
    }
}

interface SlashCmdExtension<T : AgentPlugin> : CmdExtension<Unit, SlashHolder, T> {
    override fun Meta.parser(context: Unit): SlashHolder {
        val holder = SlashHolder()
        holder.init(this@parser, context)
        val withoutSlash = input!!.removePrefix("/")
        val matched = (listOf(cmd) + alias).firstOrNull { withoutSlash.startsWith(it) } ?: cmd
        val args = buildList {
            add("--")
            addAll(
                withoutSlash.removePrefix(matched)
                    .split(" ")
                    .filter { it.isNotBlank() }
                    .map { it.trim() }
            )
        }
        holder.main(args)
        return holder
    }
}


object LoggerTerminalInterface : TerminalInterface {

    private val log = KotlinLogging.logger {}

    override fun info(
        ansiLevel: AnsiLevel?,
        hyperlinks: Boolean?,
        outputInteractive: Boolean?,
        inputInteractive: Boolean?
    ): TerminalInfo {
        return TerminalInfo(
            ansiLevel = AnsiLevel.NONE,
            outputInteractive = true,
            inputInteractive = true,
            supportsAnsiCursor = true,
            ansiHyperLinks = true
        )
    }

    override fun completePrintRequest(request: PrintRequest) {
        if (request.stderr) log.error { request.text }
        else log.info { request.text }
    }

    override fun readLineOrNull(hideInput: Boolean): String? {
        return null
    }
}

abstract class ArgParserHolder<Context> : CliktCommand() {

    companion object Empty : ArgParserHolder<Unit>() {
        override fun run() {
        }
    }

    init {
        context {
            terminal = Terminal(terminalInterface = LoggerTerminalInterface)
            exitProcess = {}
        }
    }

    open fun init(meta: Meta, context: Context) {}

}

interface RouteExtension<Plugin : AgentPlugin> : AgentExtension<Plugin> {
    val matcher: Pair<String, String>
}

interface PassiveExtension<Plugin : AgentPlugin> : AgentExtension<Plugin>

typealias Handler = suspend PluginContext.(meta: Meta) -> Unit
typealias MetaToolSetCreator = () -> MetaToolSet

interface Scheduler {
    /** 周期调度 - cron 表达式 */
    fun scheduleRecurrently(id: String, cron: String, action: () -> Unit)

    /** 周期调度 - Kotlin Duration 间隔 */
    fun scheduleRecurrently(id: String, interval: Duration, action: () -> Unit)

    /** 一次性延迟任务 */
    fun schedule(id: String, delay: Duration, action: () -> Unit)

    /** 立即入队执行 */
    fun enqueue(id: String, action: () -> Unit)

    /** 取消任务 */
    fun cancel(id: String)
}

interface PluginContext : AutoCloseable {
    val defined: PluginDef

    val mem: Mem
    val kv: Kv
    val blob: Blob
    val vector: Vector

    val config: PluginConfig

    val database: Database

    val scheduler: Scheduler

    val llm: PromptExecutor
    val http: HttpClient

    val HttpClient.proxy: HttpClient

    val server: Server

    fun chain(handler: Handler)

    fun tool(toolset: PluginContext.() -> MetaToolSetCreator)

    fun onEvent(handler: suspend (IntegrationEvent) -> Unit)

    fun registerCommandExample(example: String, description: String = "")

}

interface PluginContextBootstrap : PluginContext, AutoCloseable {
    fun start()
}

interface MetaToolSet : ToolSet {
    companion object {
        lateinit var meta: Meta
    }
}

interface PluginDef {
    val name: String
    val routeKeys: List<RouteKey>
}

sealed interface RouteKey {
    val key: String
}

class LLMRouteKey(override val key: String) : RouteKey

class CmdRouteKey(override val key: String) : RouteKey

enum class ExpireStrategy {
    AFTER_WRITE,
    AFTER_ACCESS
}

interface Mem : AutoCloseable {
    suspend fun get(key: String): String?
    suspend fun set(key: String, value: String)
    suspend fun set(key: String, value: String, expire: Duration, strategy: ExpireStrategy)
    suspend fun delete(key: String)
}

interface Kv : AutoCloseable {
    suspend fun get(key: String): String?
    suspend fun set(key: String, value: String)
    suspend fun set(key: String, value: String, expire: Duration, strategy: ExpireStrategy)
    suspend fun delete(key: String)
}

interface Blob : AutoCloseable {
    suspend fun get(path: Path): InputStream
    suspend fun set(path: Path, value: InputStream)
    suspend fun delete(path: Path)
}

interface Vector : AutoCloseable {
    suspend fun embedding(input: List<String>, images: List<ByteArray>): FloatArray
    suspend fun search(queryVector: FloatArray, topK: Int, filter: List<String>? = null): List<SearchResult>
    suspend fun upsert(id: String, content: String, tag: String, vector: FloatArray)
    suspend fun delete(id: String)
    suspend fun deleteAll()

    data class SearchResult(
        val id: String,
        val content: String,
        val tag: String,
        val score: Float
    )
}

/**
 * 插件配置接口，用于读取插件配置文件
 */
interface PluginConfig {
    private val envRegex
        get() = Regex("""\$\{\??([A-Za-z_][A-Za-z0-9_]*)}""")

    fun findEnv(key: String): String? {
        val match = envRegex.findAll(key).firstOrNull()?.groupValues[1] ?: return key
        return System.getenv(match)
    }

    suspend fun readResource(path: String): InputStream
    operator fun invoke(): Config
}

interface Meta {
    val botId: String
    val groupId: String
    val roledBot: IBotManage.RoledBot
    val input: String?
    val senderId: String?
    val echo: String?
}

interface Database {
    suspend fun getHistory(query: () -> Query): List<HistoryRecord>

    suspend fun <T> execute(block: () -> T): T
}

interface Server {
    val contextUrl: URLBuilder
    fun route(conf: Route.() -> Unit)
}

fun Meta.sendAgent(
    input: String,
    state: SendAgentStateDsl? = null,
) = sendAgent(input, EmptyConfig, state)

fun Meta.sendAgent(
    input: String,
    conf: SendAgentConfig = EmptyConfig,
    state: SendAgentStateDsl? = null
) {
    sendAgent(this.botId, this.groupId, input, conf, state)
}

fun sendAgent(
    botId: String,
    groupId: String,
    input: String,
    conf: SendAgentConfig = EmptyConfig,
    state: SendAgentStateDsl? = null
) {
    for (sender in ServiceLoader.load(AgentSender::class.java)) {
        sender.sendAgent(botId, groupId, input, conf, state)
    }
}

interface SendAgentState {
    val scope: CoroutineScope
    suspend fun callToolStart(
        toolName: String,
        toolArgs: JSONElement
    ) {

    }

    suspend fun callToolCompletion(
        toolName: String,
        toolArgs: JSONElement,
        toolResult: JSONElement?,
        toolError: String?
    ) {
    }

    suspend fun runStart() {

    }

    suspend fun runCompletion(error: Throwable?) {

    }

    suspend fun callReject() {

    }

    suspend fun callFallback() {

    }

    suspend fun callStart() {

    }

    suspend fun callCompletion(error: Throwable?) {

    }
}

@OptIn(ExperimentalUuidApi::class)
data class SendAgentConf(
    val chatVision: Boolean = false,
    val webSearch: Boolean = false,
    val toolSetBuilder: ((ChatToolSet) -> List<ToolSet>)? = null,
    val feature: ProactiveSpeakFeature = PSFeature.NONE,
    val echo: String = Uuid.random().toHexString(),
)

interface SendAgentConfig {

    operator fun SendAgentConfig.plus(config: SendAgentConfig): SendAgentConfig {
        if (config == EmptyConfig) return this
        return config.fold(this) { acc, elem ->
            val removed = acc.minusKey(elem.key)
            if (removed == EmptyConfig) config
            else CombinedConfig(removed, elem)
        }
    }

    fun <R> fold(initial: R, op: (R, Elem) -> R): R

    fun minusKey(key: Key<*>): SendAgentConfig

    operator fun <K : Elem> get(key: Key<K>): K?

    interface Key<K : Elem>

    interface Elem : SendAgentConfig {

        val key: Key<*>

        override fun minusKey(key: Key<*>): SendAgentConfig {
            return if (key == this.key) EmptyConfig else this
        }

        override fun <R> fold(initial: R, op: (R, Elem) -> R): R {
            return op(initial, this)
        }

        @Suppress("UNCHECKED_CAST")
        override fun <K : Elem> get(key: Key<K>): K? {
            return if (key == this.key) this as K else null
        }
    }

}

class CombinedConfig(
    val left: SendAgentConfig,
    val elem: SendAgentConfig.Elem
) : SendAgentConfig {

    override fun <R> fold(initial: R, op: (R, SendAgentConfig.Elem) -> R): R {
        val newInitial = left.fold(initial, op)
        return op(newInitial, elem)
    }

    override fun minusKey(key: SendAgentConfig.Key<*>): SendAgentConfig {
        if (key == elem.key) return left

        return when (val newLeft = left.minusKey(key)) {
            left -> this
            EmptyConfig -> elem
            else -> CombinedConfig(newLeft, elem)
        }
    }

    override fun <K : SendAgentConfig.Elem> get(key: SendAgentConfig.Key<K>): K? {
        elem[key]?.let { return it }
        return left[key]
    }
}

object EmptyConfig : SendAgentConfig {
    override fun <R> fold(initial: R, op: (R, SendAgentConfig.Elem) -> R): R {
        return initial
    }

    override fun minusKey(key: SendAgentConfig.Key<*>): SendAgentConfig {
        return this
    }

    override fun <K : SendAgentConfig.Elem> get(key: SendAgentConfig.Key<K>): K? {
        return null
    }

}

enum class WebSearch : SendAgentConfig.Elem {

    ENABLE, DISABLE;

    companion object Key : SendAgentConfig.Key<WebSearch>

    override val key: SendAgentConfig.Key<*>
        get() = WebSearch

}

enum class ChatVision : SendAgentConfig.Elem {

    ENABLE, DISABLE;

    companion object Key : SendAgentConfig.Key<ChatVision>

    override val key: SendAgentConfig.Key<*>
        get() = ChatVision

}

class ToolSetBuilder private constructor() : SendAgentConfig.Elem {

    private val providers = mutableListOf<(ChatToolSet) -> ToolSet>()

    val value: (ChatToolSet) -> List<ToolSet> = { chat ->
        providers.map { it(chat) }
    }

    fun tool(tool: ToolSet) {
        providers += { tool }
    }

    fun tool(block: (ChatToolSet) -> ToolSet) {
        providers += block
    }

    companion object Key : SendAgentConfig.Key<ToolSetBuilder> {
        operator fun invoke(builder: ToolSetBuilder.() -> Unit) =
            ToolSetBuilder().apply(builder)

        operator fun invoke(tool: ToolSet) =
            ToolSetBuilder().apply {
                tool(tool)
            }
    }

    override val key: SendAgentConfig.Key<*>
        get() = ToolSetBuilder
}

class Feature(val value: ProactiveSpeakFeature) : SendAgentConfig.Elem {
    companion object Key : SendAgentConfig.Key<Feature>

    override val key: SendAgentConfig.Key<*>
        get() = Feature
}

typealias SendAgentStateDsl = SendAgentStateBuilder.() -> CoroutineScope

@Suppress("UNUSED")
class SendAgentStateBuilder(
    private val holder: MutableMap<String, Any>
) {

    fun callToolStart(builder: suspend (String, JsonObject) -> Unit) {
        holder["callToolStart"] = builder
    }

    fun callToolCompletion(
        builder: suspend (
            String,
            JsonObject,
            JsonElement?,
            String?
        ) -> Unit
    ) {
        holder["callToolCompletion"] = builder
    }

    fun runStart(builder: suspend () -> Unit) {
        holder["runStart"] = builder
    }

    fun runCompletion(builder: suspend (Throwable?) -> Unit) {
        holder["runCompletion"] = builder
    }

    fun callReject(builder: suspend () -> Unit) {
        holder["callReject"] = builder
    }

    fun callFallback(builder: suspend () -> Unit) {
        holder["callFallback"] = builder
    }

    fun callStart(builder: suspend () -> Unit) {
        holder["callStart"] = builder
    }

    fun callCompletion(builder: suspend (Throwable?) -> Unit) {
        holder["callCompletion"] = builder
    }
}


interface AgentSender {

    fun sendAgent(
        botId: String,
        groupId: String,
        input: String,
        dsl: SendAgentStateDsl? = null
    )

    fun sendAgent(
        botId: String,
        groupId: String,
        input: String,
        config: SendAgentConfig = EmptyConfig,
        dsl: SendAgentStateDsl? = null
    )

}

object MetaContext : CoroutineContext.Key<MetaElement>
class MetaElement(val meta: Meta) : CoroutineContext.Element {
    override val key = MetaContext
}

fun Meta.asContextElement(): CoroutineContext.Element = MetaElement(this)
