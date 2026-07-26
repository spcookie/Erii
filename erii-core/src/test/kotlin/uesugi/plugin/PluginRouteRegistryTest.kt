package uesugi.plugin

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginRouteRegistryTest {
    @Test
    fun `registration can be replaced and unloaded without changing application routes`() = testApplication {
        lateinit var registry: PluginRouteRegistry
        application {
            registry = PluginRouteRegistry(this)
            intercept(ApplicationCallPipeline.Call) {
                registry.dispatch(this)
            }
        }
        startApplication()

        registry.register("demo") {
            get("/value/{id}") {
                call.respondText("first:${call.parameters["id"]}")
            }
        }
        registry.register("other") {
            get("/health") {
                call.respondText("healthy")
            }
        }

        assertEquals("first:42", client.get("/plugin/demo/value/42").bodyAsText())
        assertEquals("healthy", client.get("/plugin/other/health").bodyAsText())

        registry.register("demo") {
            get("/value/{id}") {
                call.respondText("second:${call.parameters["id"]}")
            }
        }

        assertEquals("second:42", client.get("/plugin/demo/value/42").bodyAsText())
        assertTrue(registry.unregister("demo"))
        assertFalse(registry.unregister("demo"))
        assertEquals(HttpStatusCode.NotFound, client.get("/plugin/demo/value/42").status)
        assertEquals("healthy", client.get("/plugin/other/health").bodyAsText())
    }

    @Test
    fun `only plugin paths are dispatched`() = testApplication {
        lateinit var registry: PluginRouteRegistry
        application {
            registry = PluginRouteRegistry(this)
            intercept(ApplicationCallPipeline.Call) {
                registry.dispatch(this)
            }
        }
        startApplication()

        registry.register("custom-prefix") {
            get("/health") {
                call.respondText("healthy")
            }
        }

        assertEquals(HttpStatusCode.NotFound, client.get("/demo/health").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/plugin/missing/health").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/plugin/demo/health").status)
        assertEquals("healthy", client.get("/plugin/custom-prefix/health").bodyAsText())
    }
}
