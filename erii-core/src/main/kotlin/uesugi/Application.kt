package uesugi

import io.ktor.server.application.*
import io.ktor.server.netty.*
import kotlinx.coroutines.runBlocking
import uesugi.cli.*
import uesugi.common.toolkit.BrowserScraperHolder
import uesugi.common.toolkit.ConfigHolder
import uesugi.common.toolkit.logger
import uesugi.config.ConfigHolderImpl
import uesugi.config.configureH2Console
import uesugi.config.registerRefreshers
import uesugi.core.bot.configureBotAgent
import uesugi.core.bot.configureConnectBots
import uesugi.core.bot.disconnectBots
import uesugi.core.chat.configureChatBridge
import uesugi.core.component.browser.BrowserScraperImpl
import uesugi.core.mcp.McpManager
import uesugi.core.route.RouteTriggerHandler
import uesugi.server.*

internal val LOG by lazy { logger("uesugi") }

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    printBanner()
    configureLogging()
    checkDefaultCredentials()
    configurePrintCliStartupInfo()

    ConfigHolder.init(ConfigHolderImpl())
    SystemConfigHolder.init(this)

    RouteTriggerHandler.start()

    val browserScraperImpl = BrowserScraperImpl()
    BrowserScraperHolder.init(browserScraperImpl)
    monitor.subscribe(ApplicationStopPreparing) {
        browserScraperImpl.close()
    }

    runBlocking {
        McpManager.load()
    }
    monitor.subscribe(ApplicationStopPreparing) {
        runBlocking { McpManager.close() }
    }

    configureFrameworks()
    configureMonitoring()
    configureSecurity()
    configureHTTP()
    configureRouting()

    configureIpc()

    configureBotAgent()

    configureConnectBots()
    monitor.subscribe(ApplicationStopPreparing) {
        disconnectBots()
    }

    registerRefreshers()

    configureH2Console()
    configureChatBridge()
}
