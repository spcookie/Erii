package uesugi.plugin.builtin.help

import io.ktor.server.jte.JteContent
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import org.koin.core.context.GlobalContext
import org.pf4j.Extension
import uesugi.common.toolkit.BrowserScraper
import uesugi.common.toolkit.BrowserScraperHolder
import uesugi.onebot.core.message.buildMessage
import uesugi.plugin.PluginHelpCatalog
import uesugi.plugin.PluginLifecycleManager
import uesugi.plugin.builtin.Builtin
import uesugi.plugin.builtin.BuiltinExtension
import uesugi.plugin.builtin.CommandQueue
import uesugi.spi.AgentExtension
import uesugi.spi.ArgParserHolder
import uesugi.spi.CmdExtension
import uesugi.spi.PluginContext
import uesugi.spi.sendMessage
import java.net.URLEncoder
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

@Extension(points = [AgentExtension::class])
class Help : CmdExtension<Unit, ArgParserHolder.Empty, Builtin>, BuiltinExtension {

    override val name: String
        get() = "builtin_help"

    override val cmd: String
        get() = "help"

    override fun onLoad(context: PluginContext) {
        val lifecycleManager = GlobalContext.get().get<PluginLifecycleManager>()
        val browserScraper = BrowserScraperHolder.getInstance()
        val pageUrl = context.server.contextUrl.buildString()

        context.server.route {
            get {
                val botId = call.request.queryParameters["botId"].orEmpty()
                val catalog = lifecycleManager.getHelpCatalog(botId)
                call.respond(renderHelpPage(catalog))
            }
        }

        context.chain { meta ->
            CommandQueue.serial("${meta.botId}:${meta.groupId}", timeout = 20.seconds) {
                val url = "$pageUrl?botId=${URLEncoder.encode(meta.botId, Charsets.UTF_8)}"
                val bytes = browserScraper.takeFullScreenshot(
                    url = url,
                    width = 1280,
                    quality = 100,
                    type = BrowserScraper.ScreenshotType.JPEG,
                    waitForNetworkIdle = true,
                )
                val base64 = Base64.getEncoder().encodeToString(bytes)
                meta.sendMessage(
                    buildMessage { image("base64://$base64") },
                )
            } ?: return@chain
        }
    }
}

internal fun renderHelpPage(catalog: PluginHelpCatalog): JteContent =
    JteContent("help.kte", mapOf("catalog" to catalog))
