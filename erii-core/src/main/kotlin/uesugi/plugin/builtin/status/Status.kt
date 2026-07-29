package uesugi.plugin.builtin.status

import io.ktor.server.config.*
import org.pf4j.Extension
import uesugi.common.toolkit.BrowserScraper
import uesugi.common.toolkit.BrowserScraperHolder
import uesugi.common.toolkit.ConfigHolder
import uesugi.onebot.core.message.buildMessage
import uesugi.onebot.sdk.client.api.sendGroupMsg
import uesugi.plugin.builtin.Builtin
import uesugi.plugin.builtin.BuiltinExtension
import uesugi.plugin.builtin.CommandQueue
import uesugi.server.SystemConfigHolder
import uesugi.spi.*
import java.util.*
import kotlin.time.Duration.Companion.seconds

@Extension(points = [AgentExtension::class])
class Status : CmdExtension<Unit, ArgParserHolder.Empty, Builtin>, BuiltinExtension {

    override val name: String
        get() = "builtin_rendering"

    override val description: String
        get() = "查看当前群组的运行状态"

    override fun onLoad(context: PluginContext) {
        val browserScraper = BrowserScraperHolder.getInstance()
        val externalHost = ConfigHolder.getBrowserExternalHost()

        val port: Int = SystemConfigHolder.config
            .property("ktor.deployment.port")
            .getAs()

        val username = SystemConfigHolder.config.property("security.username").getString()
        val password = SystemConfigHolder.config.property("security.password").getString()

        context.chain { meta ->
            if (!meta.isAdmin()) return@chain
            CommandQueue.serial("${meta.botId}:${meta.groupId}", timeout = 20.seconds) {
                val bytes = browserScraper.takeFullScreenshot(
                    url = "http://${externalHost}:${port}/view/${meta.botId}/${meta.groupId}",
                    width = 1200,
                    quality = 100,
                    type = BrowserScraper.ScreenshotType.JPEG,
                    waitForNetworkIdle = true,
                    username = username,
                    password = password
                )
                val base64 = Base64.getEncoder().encodeToString(bytes)
                meta.roledBot.refBot.sendGroupMsg(
                    meta.groupId.toLong(),
                    buildMessage { image("base64://$base64") }
                )
            } ?: return@chain
        }
    }

    override val cmd: String
        get() = "status"
}
