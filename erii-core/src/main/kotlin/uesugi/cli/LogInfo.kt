package uesugi.cli

import ch.qos.logback.classic.LoggerContext
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.ktor.server.application.*
import org.slf4j.LoggerFactory
import uesugi.LOG
import uesugi.Version
import uesugi.main


fun configureLogging() {
    val loggerContext = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
    val rootLogger = loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)

    if (System.getenv("ERII_START_MODE") == "CLI") {
        KotlinLoggingConfiguration.logStartupMessage = false
        rootLogger.detachAppender("STDOUT")
    } else {
        rootLogger.detachAppender("INFO_FILE")
        rootLogger.detachAppender("CLI_ERROR_STDOUT")
    }
}

fun Application.checkDefaultCredentials() {
    val username = environment.config.property("security.username").getString()
    val password = environment.config.property("security.password").getString()

    if (username != "eriix" || password != "@Aa123!") return

    if (System.getenv("ERII_START_MODE") == "CLI") {
        println("${yellow("[WARN]")} ${bold("Using default credentials:")} ${yellow("username=$username, password=$password")}")
        println(
            "${yellow("[WARN]")} Please change default credentials via env vars ${cyan("ERII_SERVER_USERNAME")} / ${
                cyan(
                    "ERII_SERVER_PASSWORD"
                )
            }"
        )
        println()
    } else {
        LOG.warn("Using default credentials: username=$username, password=$password")
        LOG.warn("Please change default credentials via env vars ERII_SERVER_USERNAME / ERII_SERVER_PASSWORD")
    }
}

fun Application.configurePrintCliStartupInfo() {
    if (System.getenv("ERII_START_MODE") != "CLI") return
    monitor.subscribe(ApplicationStarted) {
        if (System.getenv("ERII_START_MODE") != "CLI") return@subscribe

        val port = environment.config.property("ktor.deployment.port").getString().toInt()

        println("${bold("Erii Status")} ${gray("->")} ${cyan("http://localhost:${port}")}")

        val h2ConsoleEnabled = System.getProperty("h2.console.enabled", "false").toBoolean()
        if (h2ConsoleEnabled) {
            val h2Port = System.getProperty("h2.console.port", "8082")
            println("${bold("H2 Console")} ${gray("->")} ${cyan("http://localhost:${h2Port}")}")
        }

        val jobrunrDashboardEnabled = System.getProperty("jobrunr.dashboard.enabled", "false").toBoolean()
        if (jobrunrDashboardEnabled) {
            val jobrunrPort = System.getProperty("jobrunr.dashboard.port", "8000")
            println("${bold("JobRunr Dashboard")} ${gray("->")} ${cyan("http://localhost:${jobrunrPort}")}")
        }

        println()
        println(green("Erii started successfully!"))
    }
}

fun printBanner() {
    val banner = ::main.javaClass.classLoader.getResourceAsStream("banner.txt")?.bufferedReader()?.readText()
    if (banner != null) {
        val lines = banner.lines()
        lines.forEach { line ->
            when {
                line.isBlank() -> println()
                line.contains("{version}") -> {
                    val prefix = line.substringBefore("Version:")
                    println("${prefix}${gray("Version:")} ${cyan(Version.CURRENT)}")
                }

                else -> println(gradientText(line, "#0B3289", "#FF606C"))
            }
        }
    }
}