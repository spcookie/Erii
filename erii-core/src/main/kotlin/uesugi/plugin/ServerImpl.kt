package uesugi.plugin

import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import uesugi.common.toolkit.ConfigHolder
import uesugi.common.toolkit.logger
import uesugi.server.SystemConfigHolder
import uesugi.spi.PluginDef
import uesugi.spi.Server
import java.util.concurrent.ConcurrentHashMap

class ServerImpl(val defined: PluginDef) : Server {

    override val contextUrl: URLBuilder
        get() = URLBuilder().apply {
            protocol = URLProtocol.HTTP
            host = ConfigHolder.getBrowserExternalHost()
            port = _port
            pathSegments = listOf("plugin", defined.name)
        }

    companion object {
        private val log = logger()
        private val _port by lazy {
            SystemConfigHolder.config.property("ktor.deployment.port").getString().toInt() + 100
        }

        private val routeing by lazy {
            var ref: Route? = null
            embeddedServer(Netty, configure = {
                connectors.add(EngineConnectorBuilder().apply {
                    host = "0.0.0.0"
                    port = _port
                })
                connectionGroupSize = 2
                workerGroupSize = 5
                callGroupSize = 10
            }) {
                install(ContentNegotiation) {
                    jackson()
                }

                routing {
                    route("/plugin") {
                        ref = this
                    }
                }
            }.start()
            ref!!
        }

        private val pluginRoutes = ConcurrentHashMap<String, Route>()

        private val childrenField by lazy {
            Route::class.java.getDeclaredField("_children").apply {
                isAccessible = true
            }
        }

        fun clearPluginRoutes(pluginName: String) {
            val route = pluginRoutes.remove(pluginName)
            if (route != null) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val children = childrenField.get(route) as MutableList<Route>
                    children.clear()
                } catch (e: Exception) {
                    log.warn("Failed to clear plugin routes for $pluginName", e)
                }
            }
        }
    }

    override fun route(conf: Route.() -> Unit) {
        val existing = pluginRoutes[defined.name]
        if (existing != null) {
            try {
                @Suppress("UNCHECKED_CAST")
                val children = childrenField.get(existing) as MutableList<Route>
                children.clear()
            } catch (e: Exception) {
                log.warn("Failed to clear route children for ${defined.name}, falling back to new node", e)
                val route = routeing.route("/${defined.name}") { conf() }
                pluginRoutes[defined.name] = route
                return
            }
            with(existing) { conf() }
        } else {
            val route = routeing.route("/${defined.name}") { conf() }
            pluginRoutes[defined.name] = route
        }
    }

}
