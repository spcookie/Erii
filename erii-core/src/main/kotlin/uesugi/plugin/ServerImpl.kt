package uesugi.plugin

import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.jte.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import uesugi.common.toolkit.ConfigHolder
import uesugi.server.SystemConfigHolder
import uesugi.server.createJteTemplateEngine
import uesugi.spi.PluginDef
import uesugi.spi.Server

class ServerImpl(val defined: PluginDef) : Server {

    private val prefixOwner = Any()

    @Volatile
    private var effectivePrefix = defined.name

    private var prefixRegistered = false
    private var routeRegistered = false
    private var routeConfiguration: (Route.() -> Unit)? = null

    override val contextUrl: URLBuilder
        get() = URLBuilder().apply {
            protocol = URLProtocol.HTTP
            host = ConfigHolder.getBrowserExternalHost()
            port = _port
            pathSegments = listOf("plugin", effectivePrefix)
        }

    companion object {
        private val serverLock = Any()

        private val _port by lazy {
            SystemConfigHolder.config.property("ktor.deployment.port").getString().toInt() + 100
        }

        @Volatile
        private var registry: PluginRouteRegistry? = null

        private val prefixManager = PluginRoutePrefixManager()

        private fun registryLocked(): PluginRouteRegistry {
            registry?.let { return it }

            var createdRegistry: PluginRouteRegistry? = null
            embeddedServer(Netty, configure = {
                connectors.add(EngineConnectorBuilder().apply {
                    host = "0.0.0.0"
                    port = _port
                })
                connectionGroupSize = 2
                workerGroupSize = 5
                callGroupSize = 10
            }) {
                install(Jte) {
                    templateEngine = createJteTemplateEngine()
                }
                install(ContentNegotiation) {
                    jackson()
                }

                val routeRegistry = PluginRouteRegistry(this)
                createdRegistry = routeRegistry
                intercept(ApplicationCallPipeline.Call) {
                    routeRegistry.dispatch(this)
                }
            }.start()

            return checkNotNull(createdRegistry) {
                "Plugin HTTP server started without initializing its route registry"
            }.also { registry = it }
        }
    }

    override fun registerPrefix(prefix: String) {
        synchronized(serverLock) {
            val previousPrefix = effectivePrefix
            val selectedPrefix = prefixManager.register(prefixOwner, defined.name, prefix)
            prefixRegistered = true
            effectivePrefix = selectedPrefix

            val conf = routeConfiguration
            if (routeRegistered && previousPrefix != selectedPrefix && conf != null) {
                registry?.unregister(previousPrefix)
                registryLocked().register(selectedPrefix, conf)
            }
        }
    }

    override fun route(conf: Route.() -> Unit) {
        synchronized(serverLock) {
            if (!prefixRegistered) {
                effectivePrefix = prefixManager.register(prefixOwner, defined.name, defined.name)
                prefixRegistered = true
            }
            routeConfiguration = conf
            registryLocked().register(effectivePrefix, conf)
            routeRegistered = true
        }
    }

    internal fun close() {
        synchronized(serverLock) {
            if (routeRegistered) {
                registry?.unregister(effectivePrefix)
            }
            routeRegistered = false
            routeConfiguration = null
            prefixManager.unregister(prefixOwner)
            prefixRegistered = false
            effectivePrefix = defined.name
        }
    }
}
