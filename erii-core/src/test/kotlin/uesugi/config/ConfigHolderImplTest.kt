package uesugi.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigHolderImplTest {
    @Test
    fun `embedding model has default when local config omits it`() {
        withConfig(
            """
            embedding {
              api-key = "test"
              url = "https://example.test/embeddings"
              provider = "ark"
            }
            """.trimIndent()
        ) {
            assertEquals("doubao-embedding-vision-251215", ConfigHolderImpl().getEmbeddingModel())
        }
    }

    @Test
    fun `embedding model can be overridden by local config`() {
        withConfig(
            """
            embedding {
              api-key = "test"
              url = "https://example.test/embeddings"
              provider = "ark"
              model = "custom-embedding-model"
            }
            """.trimIndent()
        ) {
            assertEquals("custom-embedding-model", ConfigHolderImpl().getEmbeddingModel())
        }
    }

    @Test
    fun `generic double reader accepts numeric pricing values`() {
        withConfig(
            """
            llm.usage-pricing {
              lite.input-cache-hit = 0.01875
              lite.input-cache-miss = "0.075"
            }
            """.trimIndent()
        ) {
            val config = ConfigHolderImpl()
            assertEquals(0.01875, config.getDouble("llm.usage-pricing.lite.input-cache-hit"))
            assertEquals(0.075, config.getDouble("llm.usage-pricing.lite.input-cache-miss"))
        }
    }

    @Test
    fun `stt configuration exposes provider api key url and model`() {
        withConfig(
            """
            stt {
              provider = "test-stt"
              api-key = "secret"
              url = "https://example.test/audio/transcriptions"
              model = "test-model"
            }
            """.trimIndent()
        ) {
            val config = ConfigHolderImpl()
            assertEquals("test-stt", config.getSttProvider())
            assertEquals("secret", config.getSttApiKey())
            assertEquals("https://example.test/audio/transcriptions", config.getSttUrl())
            assertEquals("test-model", config.getSttModel())
        }
    }

    @Test
    fun `stt provider defaults to none when configuration is absent`() {
        withConfig("") {
            val config = ConfigHolderImpl()
            assertEquals("none", config.getSttProvider())
            assertEquals("", config.getSttApiKey())
            assertEquals("", config.getSttUrl())
            assertEquals("", config.getSttModel())
        }
    }

    @Test
    fun `audio capability can be disabled at default and every model tier`() {
        withConfig(
            """
            llm.capability {
              audio = false
              lite.audio = false
              flash.audio = false
              pro.audio = false
            }
            """.trimIndent()
        ) {
            val config = ConfigHolderImpl()
            assertEquals(false, config.isLlmCapabilityEnabled("audio"))
            assertEquals(false, config.isLlmCapabilityEnabled("lite", "audio"))
            assertEquals(false, config.isLlmCapabilityEnabled("flash", "audio"))
            assertEquals(false, config.isLlmCapabilityEnabled("pro", "audio"))
        }
    }

    private fun withConfig(content: String, block: () -> Unit) {
        val previous = System.getProperty("config.path")
        val config = Files.createTempFile("erii-config", ".conf")
        Files.writeString(config, content)
        try {
            System.setProperty("config.path", config.toString())
            block()
        } finally {
            if (previous == null) {
                System.clearProperty("config.path")
            } else {
                System.setProperty("config.path", previous)
            }
            Files.deleteIfExists(config)
        }
    }
}
