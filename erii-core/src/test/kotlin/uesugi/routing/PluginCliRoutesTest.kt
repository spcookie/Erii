package uesugi.routing

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import uesugi.common.EventBus
import uesugi.common.EventDispatchException
import uesugi.common.event.CliPluginEvent
import uesugi.common.event.CliPluginReplyEvent
import uesugi.common.toolkit.JSON
import uesugi.plugin.PluginCommandExampleRegistry
import java.util.concurrent.TimeoutException
import kotlin.system.measureTimeMillis
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PluginCliRoutesTest {
    @AfterTest
    fun cleanUp() {
        PluginCommandExampleRegistry.clear()
    }

    @Test
    fun `http match and send complete the registry event and reply flow`() {
        PluginCommandExampleRegistry.register("demo", "DemoExtension", "/demo ping", "Ping demo")
        val eventHandler = EventBus.subscribeSync<CliPluginEvent> { event ->
            EventBus.postSync(CliPluginReplyEvent(event.echo, "pong:${event.input}"))
        }
        try {
            testApplication {
                application {
                    install(ContentNegotiation) { json(JSON) }
                    routing { configurePluginCliRoutes() }
                }

                val matchResponse = client.get("/api/plugins/cli/match?query=ping&limit=20")
                assertEquals(HttpStatusCode.OK, matchResponse.status)
                val matchJson = JSON.parseToJsonElement(matchResponse.bodyAsText()).jsonObject
                val match = matchJson.getValue("matches").jsonArray.single().jsonObject
                assertEquals("demo", match.getValue("pluginId").jsonPrimitive.content)
                assertEquals("DemoExtension", match.getValue("extensionName").jsonPrimitive.content)
                assertEquals("/demo ping", match.getValue("example").jsonPrimitive.content)

                val sendResponse = client.post("/api/plugins/cli/send") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"input":" /demo ping "}""")
                }
                assertEquals(HttpStatusCode.OK, sendResponse.status)
                val sendJson = JSON.parseToJsonElement(sendResponse.bodyAsText()).jsonObject
                assertEquals("ok", sendJson.getValue("status").jsonPrimitive.content)
                assertEquals("/demo ping", sendJson.getValue("input").jsonPrimitive.content)
                assertEquals("pong:/demo ping", sendJson.getValue("reply").jsonPrimitive.content)
                assertTrue(sendJson.getValue("echo").jsonPrimitive.content.isNotBlank())
            }
        } finally {
            EventBus.unsubscribeSync(eventHandler)
        }
    }

    @Test
    fun `subscriber failure is returned by the dispatch contract`() = runBlocking {
        val eventHandler = EventBus.subscribeSync<CliPluginEvent> {
            error("broken plugin")
        }
        try {
            val result = dispatchCliPluginEvent("fail", timeoutMillis = 1_000)

            val failure = assertIs<EventDispatchException>(result.failure)
            assertEquals("broken plugin", failure.cause?.message)
        } finally {
            EventBus.unsubscribeSync(eventHandler)
        }
    }

    @Test
    fun `subscriber failure becomes an http error response`() {
        val eventHandler = EventBus.subscribeSync<CliPluginEvent> {
            error("broken plugin")
        }
        try {
            testApplication {
                application {
                    install(ContentNegotiation) { json(JSON) }
                    routing { configurePluginCliRoutes() }
                }

                val response = client.post("/api/plugins/cli/send") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"input":"fail"}""")
                }

                assertEquals(HttpStatusCode.InternalServerError, response.status)
                val responseJson = JSON.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals("error", responseJson.getValue("status").jsonPrimitive.content)
                assertEquals("plugin event handler failed", responseJson.getValue("message").jsonPrimitive.content)
            }
        } finally {
            EventBus.unsubscribeSync(eventHandler)
        }
    }

    @Test
    fun `dispatch times out even when subscriber ignores interruption`() {
        val eventHandler = EventBus.subscribeSync<CliPluginEvent> {
            val stopAt = System.nanoTime() + 500_000_000L
            while (System.nanoTime() < stopAt) {
                // Deliberately ignore the interrupted flag to model an uncooperative plugin.
            }
        }
        try {
            lateinit var result: CliPluginDispatchResult
            val elapsed = measureTimeMillis {
                result = runBlocking { dispatchCliPluginEvent("slow", timeoutMillis = 50) }
            }

            assertIs<TimeoutException>(result.failure)
            assertTrue(elapsed < 1_000, "dispatch took ${elapsed}ms")
        } finally {
            EventBus.unsubscribeSync(eventHandler)
        }
    }
}
