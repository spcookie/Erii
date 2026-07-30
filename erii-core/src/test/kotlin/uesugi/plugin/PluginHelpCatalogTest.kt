package uesugi.plugin

import uesugi.spi.AgentPlugin
import uesugi.spi.ArgParserHolder
import uesugi.spi.CmdExtension
import uesugi.spi.PluginContext
import uesugi.spi.RouteExtension
import kotlin.test.Test
import kotlin.test.assertEquals

class PluginHelpCatalogTest {

    @Test
    fun `builtin commands are hidden while routes remain available`() {
        val catalog = buildPluginHelpCatalog(
            mapOf(
                "builtin" to listOf(
                    TestCommand("help"),
                    TestRoute("CHAT"),
                ),
                "weather" to listOf(
                    TestCommand("weather"),
                    TestRoute("WEATHER"),
                ),
            )
        )

        assertEquals(listOf("weather"), catalog.commands.map { it.name })
        assertEquals(listOf("CHAT", "WEATHER"), catalog.routes.map { it.name })
    }
}

private class TestPlugin : AgentPlugin()

private class TestCommand(
    override val cmd: String,
) : CmdExtension<Unit, ArgParserHolder.Empty, TestPlugin> {
    override fun onLoad(context: PluginContext) = Unit
}

private class TestRoute(
    private val routeName: String,
) : RouteExtension<TestPlugin> {
    override val matcher: Pair<String, String> = routeName to routeName

    override fun onLoad(context: PluginContext) = Unit
}
