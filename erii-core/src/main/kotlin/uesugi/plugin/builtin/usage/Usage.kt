package uesugi.plugin.builtin.usage

import io.ktor.server.config.*
import org.pf4j.Extension
import uesugi.common.toolkit.BrowserScraper
import uesugi.common.toolkit.BrowserScraperHolder
import uesugi.common.toolkit.ConfigHolder
import uesugi.onebot.sdk.client.api.getGroupList
import uesugi.onebot.sdk.client.api.sendGroupMsg
import uesugi.onebot.core.message.buildMessage
import uesugi.plugin.builtin.Builtin
import uesugi.plugin.builtin.BuiltinExtension
import uesugi.plugin.builtin.CommandQueue
import uesugi.server.SystemConfigHolder
import uesugi.spi.*
import java.net.URLEncoder
import java.util.*
import kotlin.time.Duration.Companion.seconds

@Extension(points = [AgentExtension::class])
class Usage : CmdExtension<Unit, ArgParserHolder.Empty, Builtin>, BuiltinExtension {

    override val name: String
        get() = "builtin_usage"

    override val cmd: String
        get() = "usage"

    override fun onLoad(context: PluginContext) {
        context.chain { meta ->
            if (!meta.isAdmin()) return@chain
            CommandQueue.serial("${meta.botId}:${meta.groupId}", timeout = 20.seconds) {
                val groupName = resolveGroupName(meta)
                val url = buildString {
                    append("http://${externalHost}:${port}/usage")
                    append("?botId=${meta.botId}")
                    append("&groupId=${meta.groupId}")
                    append("&botName=${URLEncoder.encode(meta.roledBot.role.name, "UTF-8")}")
                    append("&groupName=${URLEncoder.encode(groupName, "UTF-8")}")
                }
                renderUsage(meta, url)
            } ?: return@chain
        }
    }
}

@Extension(points = [AgentExtension::class])
class UsageAll : CmdExtension<Unit, ArgParserHolder.Empty, Builtin>, BuiltinExtension {

    override val name: String
        get() = "builtin_usage_all"

    override val description: String
        get() = "查看全部群组的模型用量"

    override val cmd: String
        get() = "usage-all"

    override fun onLoad(context: PluginContext) {
        context.chain { meta ->
            CommandQueue.serialDedup(meta.groupId) {
                val url = "http://${externalHost}:${port}/usage"
                renderUsage(meta, url)
            } ?: return@chain
        }
    }
}

private val externalHost: String
    get() = ConfigHolder.getBrowserExternalHost()

private val port: Int
    get() = SystemConfigHolder.config.property("ktor.deployment.port").getAs()

private val username: String
    get() = SystemConfigHolder.config.property("security.username").getString()

private val password: String
    get() = SystemConfigHolder.config.property("security.password").getString()

private suspend fun resolveGroupName(meta: Meta): String {
    return runCatching {
        meta.roledBot.refBot.getGroupList()
            .find { it.groupId.toString() == meta.groupId }
            ?.groupName
    }.getOrNull() ?: meta.groupId
}

private suspend fun renderUsage(meta: Meta, url: String) {
    val browserScraper = BrowserScraperHolder.getInstance()
    val bytes = browserScraper.takeFullScreenshot(
        url = url,
        width = 1280,
        height = 1500,
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
}
