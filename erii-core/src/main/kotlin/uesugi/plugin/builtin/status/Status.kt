package uesugi.plugin.builtin.status

import io.ktor.server.config.*
import org.pf4j.Extension
import uesugi.common.data.Channel
import uesugi.common.toolkit.BrowserScraper
import uesugi.common.toolkit.BrowserScraperHolder
import uesugi.common.toolkit.ConfigHolder
import uesugi.onebot.core.message.buildMessage
import uesugi.onebot.sdk.client.api.getGroupList
import uesugi.onebot.sdk.client.api.getStrangerInfo
import uesugi.plugin.builtin.Builtin
import uesugi.plugin.builtin.BuiltinExtension
import uesugi.plugin.builtin.CommandQueue
import uesugi.server.SystemConfigHolder
import uesugi.spi.*
import java.net.URLEncoder
import java.util.*
import kotlin.time.Duration.Companion.seconds

@Extension(points = [AgentExtension::class])
class Status : CmdExtension<Unit, ArgParserHolder.Empty, Builtin>, BuiltinExtension {

    override val name: String
        get() = "builtin_rendering"

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
                val groupName = resolveGroupName(meta)
                val url = buildString {
                    append("http://${externalHost}:${port}/view")
                    append("?botId=${meta.botId}")
                    append("&groupId=${meta.groupId}")
                    append("&botName=${URLEncoder.encode(meta.roledBot.role.name, "UTF-8")}")
                    append("&groupName=${URLEncoder.encode(groupName, "UTF-8")}")
                }
                val bytes = browserScraper.takeFullScreenshot(
                    url = url,
                    width = 1200,
                    quality = 100,
                    type = BrowserScraper.ScreenshotType.JPEG,
                    waitForNetworkIdle = true,
                    username = username,
                    password = password
                )
                val base64 = Base64.getEncoder().encodeToString(bytes)
                meta.sendMessage(
                    buildMessage { image("base64://$base64") }
                )
            } ?: return@chain
        }
    }

    private suspend fun resolveGroupName(meta: Meta): String {
        if (Channel.isPrivate(meta.groupId)) {
            val userId = Channel.extractUserId(meta.groupId)!!
            return meta.roledBot.refBot.getStrangerInfo(userId).nickname
        }
        return runCatching {
            meta.roledBot.refBot.getGroupList()
                .find { it.groupId.toString() == meta.groupId }
                ?.groupName
        }.getOrNull() ?: meta.groupId
    }

    override val cmd: String
        get() = "status"
}
