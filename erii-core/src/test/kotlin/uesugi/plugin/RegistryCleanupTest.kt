package uesugi.plugin

import uesugi.core.route.CmdRuleRegister
import uesugi.core.route.MetaToolSetRegister
import uesugi.core.route.RouteRuleRegister
import uesugi.spi.AgentPlugin
import uesugi.spi.MetaToolSet
import uesugi.spi.PluginContext
import uesugi.spi.RouteExtension
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RegistryCleanupTest {
    @AfterTest
    fun cleanUp() {
        ExtensionRegister.clear()
        RouteRuleRegister.clear()
        CmdRuleRegister.clear()
        MetaToolSetRegister.clear()
        PluginCommandExampleRegistry.clear()
    }

    @Test
    fun `extension register clear removes plugin groups`() {
        val extension = TestRouteExtension("demo_route")

        ExtensionRegister.add("demo", extension)
        ExtensionRegister.clear()

        assertEquals(emptyList(), ExtensionRegister.getAllExtensions())
        assertEquals(emptyMap(), ExtensionRegister.getAllPlugins())
    }

    @Test
    fun `registries remove entries by plugin id`() {
        RouteRuleRegister.addRule("DEMO_ROUTE", "demo route", "demo")
        RouteRuleRegister.addRule("OTHER_ROUTE", "other route", "other")
        CmdRuleRegister.addRule("demo", "demo")
        CmdRuleRegister.addRule("other", "other")
        MetaToolSetRegister.addToolSet("demo_tool") { meta -> TestMetaToolSet(meta) }
        MetaToolSetRegister.addToolSet("other_tool") { meta -> TestMetaToolSet(meta) }
        PluginCommandExampleRegistry.register("demo", "demo_ext", "demo ping", "demo command")
        PluginCommandExampleRegistry.register("other", "other_ext", "other ping", "other command")

        RouteRuleRegister.removePlugin("demo")
        CmdRuleRegister.removePlugin("demo")
        MetaToolSetRegister.removePlugin("demo")
        PluginCommandExampleRegistry.removePlugin("demo")

        assertNull(RouteRuleRegister.getRule("DEMO_ROUTE"))
        assertEquals("OTHER_ROUTE", RouteRuleRegister.getRule("OTHER_ROUTE")?.name)
        assertNull(CmdRuleRegister.getRule("demo"))
        assertEquals("other", CmdRuleRegister.getRule("other")?.name)
        assertNull(MetaToolSetRegister.getToolSet("demo_tool"))
        assertEquals(1, MetaToolSetRegister.getAllToolSets().size)
        assertEquals(listOf("other ping"), PluginCommandExampleRegistry.match("ping").map { it.example })
    }
}

private class TestMetaToolSet(override val meta: uesugi.spi.Meta) : MetaToolSet

private class TestAgentPlugin : AgentPlugin()

private class TestRouteExtension(override val name: String) : RouteExtension<TestAgentPlugin> {
    override val matcher: Pair<String, String> = "DEMO_ROUTE" to "demo route"

    override fun onLoad(context: PluginContext) = Unit
}
