package uesugi.plugin

import io.ktor.http.decodeURLPart
import io.ktor.server.application.Application
import io.ktor.server.application.PipelineCall
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.route
import io.ktor.util.pipeline.PipelineContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps plugin routing trees outside the application's permanent routing tree.
 *
 * Each registration builds a complete, isolated tree and publishes it with one atomic map update. Requests that are
 * already using the previous tree can finish, while subsequent requests immediately see the replacement. Removing a
 * map entry therefore unloads both the handlers and every object captured by their route configuration.
 */
internal class PluginRouteRegistry(
    private val application: Application,
) {
    private val routes = ConcurrentHashMap<String, RoutingRoot>()

    fun register(pluginName: String, conf: Route.() -> Unit) {
        val pluginRoutes = RoutingRoot(application).apply {
            route("/plugin/$pluginName") {
                conf()
            }
        }
        routes[pluginName] = pluginRoutes
    }

    fun unregister(pluginName: String): Boolean = routes.remove(pluginName) != null

    suspend fun dispatch(context: PipelineContext<Unit, PipelineCall>): Boolean {
        val pluginName = pluginNameFromPath(context.call.request.path()) ?: return false
        val pluginRoutes = routes[pluginName] ?: return false
        pluginRoutes.interceptor(context)
        return true
    }
}

internal fun pluginNameFromPath(path: String): String? {
    if (!path.startsWith(PLUGIN_PATH_PREFIX)) return null
    val encodedName = path.substring(PLUGIN_PATH_PREFIX.length).substringBefore('/')
    if (encodedName.isEmpty()) return null
    return runCatching { encodedName.decodeURLPart() }.getOrNull()
}

private const val PLUGIN_PATH_PREFIX = "/plugin/"
