package uesugi.config

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class PluginConfigPathTest {

    @Test
    fun `plugin config directory defaults to conf plugin`() {
        assertEquals(
            File("conf", "plugin").toPath().toAbsolutePath().toString(),
            resolvePluginConfigDir(null, null)
        )
    }

    @Test
    fun `system property overrides environment plugin config directory`() {
        assertEquals(
            File("property-plugin-dir").toPath().toAbsolutePath().toString(),
            resolvePluginConfigDir("property-plugin-dir", "environment-plugin-dir")
        )
    }
}
