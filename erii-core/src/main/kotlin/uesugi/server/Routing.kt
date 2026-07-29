package uesugi.server

import gg.jte.TemplateEngine
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.http.content.*
import io.ktor.server.jte.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.doublereceive.*
import io.ktor.server.resources.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import uesugi.common.toolkit.JSON
import uesugi.routing.*
import java.nio.file.Path
import gg.jte.ContentType as JteContentType

fun Application.configureRouting() {
    install(WebSockets) {
        pingPeriodMillis = 30_000  // 每 30 秒发一次 ping，保持 WebSocket 连接活跃
        timeoutMillis = 15_000     // pong 超时 15 秒
    }
    install(Resources)
    install(DoubleReceive)
    install(AutoHeadResponse)
    install(ContentNegotiation) {
        json(JSON)
    }
    install(Jte) {
        templateEngine = createJteTemplateEngine()
    }
    routing {
        configureBotStatus()
        configureBotInfoRoutes()
        configureBotStatusManager()
        configureBotConfigManager()
        configureChatRoutes()
        configureUsageRoutes()
        authenticate("basic") {
            staticResources("/", "assets")
        }
    }
}

fun createJteTemplateEngine(): TemplateEngine {
    val isDevelopment = System.getProperty("intellij.debug.agent") != null
    return if (isDevelopment) {
        val targetDirectory: Path = Path.of("erii-core/jte-classes")
        TemplateEngine.createPrecompiled(targetDirectory, JteContentType.Html)
    } else {
        TemplateEngine.createPrecompiled(JteContentType.Html)
    }
}
