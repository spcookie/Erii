package uesugi.routing

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import org.koin.ktor.ext.inject
import uesugi.common.EventBus
import uesugi.common.EventDispatchException
import uesugi.common.RefreshManager
import uesugi.common.event.CliPluginEvent
import uesugi.common.event.CliPluginReplyEvent
import uesugi.common.toolkit.logger
import uesugi.plugin.PluginCommandExample
import uesugi.plugin.PluginCommandExampleRegistry
import uesugi.plugin.PluginLifecycleManager
import uesugi.plugin.PluginRefreshResult
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

private val LOG = logger("PluginCli")

fun Routing.configureBotConfigManager() {
    val pluginLifecycleManager by inject<PluginLifecycleManager>()

    authenticate("basic") {
        post("/api/config/refresh") {
            val results = RefreshManager.refreshAll()
            call.respond(
                buildJsonObject {
                    put("status", "ok")
                    put("message", "config refreshed")
                    results.forEach { (k, v) -> put(k, v.toJsonElement()) }
                })
        }

        post("/api/plugins/refresh") {
            val result = pluginLifecycleManager.refreshAll()
            call.respond(result.httpStatus(), result.toJson())
        }

        post("/api/plugins/{id}/refresh") {
            val pluginId = call.parameters["id"].orEmpty()
            val result = pluginLifecycleManager.refreshPlugin(pluginId)
            call.respond(result.httpStatus(), result.toJson())
        }

        configurePluginCliRoutes()
    }
}

internal fun Route.configurePluginCliRoutes() {
    post("/api/plugins/cli/send") {
        val request = call.receive<CliPluginSendRequest>()
        val input = request.input.trim()
        if (input.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                buildJsonObject {
                    put("status", "error")
                    put("message", "input must not be blank")
                },
            )
            return@post
        }

        val result = dispatchCliPluginEvent(input)
        val statusCode = when (result.failure) {
            null -> HttpStatusCode.OK
            is TimeoutException -> HttpStatusCode.GatewayTimeout
            is RejectedExecutionException -> HttpStatusCode.ServiceUnavailable
            else -> HttpStatusCode.InternalServerError
        }
        result.failure?.let { failure ->
            LOG.warn("Plugin CLI event ${result.echo} failed", failure)
        }
        call.respond(
            statusCode,
            buildJsonObject {
                put("status", if (result.failure == null) "ok" else "error")
                put(
                    "message", when (result.failure) {
                        null -> "plugin event sent"
                        is TimeoutException -> "plugin event timed out"
                        is RejectedExecutionException -> "plugin event dispatcher is busy"
                        is EventDispatchException -> "plugin event handler failed"
                        else -> "plugin event dispatch failed"
                    }
                )
                put("input", input)
                put("echo", result.echo)
                put("reply", result.reply?.let { JsonPrimitive(it) } ?: JsonNull)
            },
        )
    }

    get("/api/plugins/cli/match") {
        val query = call.request.queryParameters["query"].orEmpty()
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(0, 100) ?: 20
        val matches = PluginCommandExampleRegistry.match(query, limit)
        call.respond(
            buildJsonObject {
                put("status", "ok")
                put("query", query)
                putJsonArray("matches") {
                    matches.forEach { add(it.toJson()) }
                }
            },
        )
    }
}

@Serializable
data class CliPluginSendRequest(
    val input: String,
)

internal data class CliPluginDispatchResult(
    val echo: String,
    val reply: String?,
    val failure: Throwable?,
)

internal suspend fun dispatchCliPluginEvent(
    input: String,
    timeoutMillis: Long = CLI_PLUGIN_EVENT_TIMEOUT_MILLIS,
): CliPluginDispatchResult {
    val echo = UUID.randomUUID().toString()
    val replies = CopyOnWriteArrayList<String?>()
    val replyHandler = EventBus.subscribeSync<CliPluginReplyEvent> { event ->
        if (event.echo == echo) {
            replies += event.message
        }
    }
    var dispatchTask: Future<*>? = null
    val failure = try {
        dispatchTask = CLI_PLUGIN_EVENT_EXECUTOR.submit {
            EventBus.postSyncOrThrow(CliPluginEvent(input, echo))
        }
        runInterruptible(Dispatchers.IO) {
            dispatchTask.get(timeoutMillis, TimeUnit.MILLISECONDS)
        }
        null
    } catch (failure: TimeoutException) {
        failure
    } catch (failure: ExecutionException) {
        unwrapExecutionFailure(failure)
    } catch (failure: EventDispatchException) {
        failure
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        failure
    } finally {
        dispatchTask?.let { task ->
            if (!task.isDone) {
                task.cancel(true)
            }
            (task as? Runnable)?.let(CLI_PLUGIN_EVENT_EXECUTOR::remove)
        }
        EventBus.unsubscribeSync(replyHandler)
    }
    return CliPluginDispatchResult(echo, replies.lastOrNull(), failure)
}

private val cliPluginEventThreadCounter = AtomicInteger()
private val CLI_PLUGIN_EVENT_EXECUTOR = ThreadPoolExecutor(
    CLI_PLUGIN_EVENT_THREADS,
    CLI_PLUGIN_EVENT_THREADS,
    0L,
    TimeUnit.MILLISECONDS,
    ArrayBlockingQueue(CLI_PLUGIN_EVENT_QUEUE_CAPACITY),
    { task ->
        Thread(task, "plugin-cli-event-${cliPluginEventThreadCounter.incrementAndGet()}").apply {
            isDaemon = true
        }
    },
    ThreadPoolExecutor.AbortPolicy(),
)

private const val CLI_PLUGIN_EVENT_THREADS = 4
private const val CLI_PLUGIN_EVENT_QUEUE_CAPACITY = 64
private const val CLI_PLUGIN_EVENT_TIMEOUT_MILLIS = 5_000L

private tailrec fun unwrapExecutionFailure(failure: Throwable): Throwable =
    if (failure is ExecutionException && failure.cause != null) {
        unwrapExecutionFailure(failure.cause!!)
    } else {
        failure
    }

private fun PluginRefreshResult.httpStatus(): HttpStatusCode = when (status) {
    "ok" -> HttpStatusCode.OK
    "not_found" -> HttpStatusCode.NotFound
    "unsupported" -> HttpStatusCode.BadRequest
    else -> HttpStatusCode.InternalServerError
}

private fun PluginRefreshResult.toJson(): JsonObject = buildJsonObject {
    put("status", status)
    put("message", message)
    requestedPluginId?.let { put("requestedPluginId", it) }
    putJsonArray("refreshedPlugins") {
        refreshedPlugins.forEach { add(it) }
    }
    put("loadedExtensions", loadedExtensions)
    putJsonObject("failedPlugins") {
        failedPlugins.forEach { (pluginId, reason) -> put(pluginId, reason) }
    }
}

private fun PluginCommandExample.toJson(): JsonObject = buildJsonObject {
    put("pluginId", pluginId)
    put("extensionName", extensionName)
    put("example", example)
    put("description", description)
}

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    is Map<*, *> -> buildJsonObject {
        this@toJsonElement.forEach { (k, v) -> put(k.toString(), v.toJsonElement()) }
    }

    is Iterable<*> -> buildJsonArray {
        this@toJsonElement.forEach { add(it.toJsonElement()) }
    }

    else -> {
        runCatching {
            @OptIn(InternalSerializationApi::class) @Suppress("UNCHECKED_CAST") Json.encodeToJsonElement(
                this::class.serializer() as KSerializer<Any>,
                this
            )
        }.getOrElse { JsonPrimitive(this.toString()) }
    }
}
