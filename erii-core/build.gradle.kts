plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.jte)
}

group = "uesugi"
version = "1.0.0"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

dependencies {
    kapt(libs.autoservice.processor)
    implementation(project(":erii-common"))
    implementation(project(":erii-spi:erii-spi-core"))
    implementation(project(":erii-spi:erii-spi-annotation"))
    // 聊天机器人
    implementation("uesugi:onebot-sdk:1.0.0")
    implementation("uesugi:onebot-mock:1.0.0")
    // 定时任务
    implementation(libs.jobrunr)
    // AI框架
    implementation(libs.koog.agents) {
        exclude("io.ktor", "ktor-client-apache5")
    }
    implementation(libs.koog.agents.mcp)
    // 依赖注入
    implementation(libs.koin.core)
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)
    // 数据库
    implementation(libs.h2)
    implementation(libs.hikari)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.json)
    implementation(libs.exposed.kotlin.datetime)
    implementation(libs.exposed.migration.core)
    implementation(libs.exposed.migration.jdbc)
    // 服务端
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.resources)
    implementation(libs.ktor.server.double.receive)
    implementation(libs.ktor.server.auto.head.response)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.partial.content)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.netty)
    implementation(libs.logback.classic)
    implementation(libs.kotlinx.serialization.msgpack)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.ktor.server.jte)
    implementation(libs.ktor.server.websockets)
    // 客户端
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.serialization.jackson)
    implementation(libs.ktor.client.logging)
    // 工具
    implementation(libs.atomicfu)
    implementation(libs.mapdb)
    implementation(libs.caffeine)
    implementation(libs.arrow.core)
    implementation(libs.lucene.core)
    implementation(libs.lucene.analyzers.common)
    implementation(libs.lucene.analyzers.smartcn)
    implementation(libs.playwright)
    implementation(libs.flexmark.html2md)
    implementation(libs.flexmark.ext.tables)
    implementation(libs.clikt)
    implementation(libs.lunar)
    implementation(libs.typesafe.config)
    implementation(libs.snakeyaml)
    implementation(libs.okio)
    implementation(libs.pf4j)
    implementation(libs.kotlin.logging)
    implementation(libs.thumbnailator)
    implementation(libs.commons.pool2)
    implementation(libs.rdf4j.sail.nativerdf)
    implementation(libs.rdf4j.repository.sail)
    implementation(libs.rdf4j.queryparser.sparql)
    compileOnly(libs.jte.kotlin)
    compileOnly(libs.autoservice.annotations)
    // 测试
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
}

val generateVersionFile by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/source/version/kotlin")

    outputs.dir(outputDir)

    doLast {
        val file = outputDir.get().file("uesugi/Version.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package uesugi

            object Version {
                const val CURRENT = "${project.version}"
            }
            """.trimIndent()
        )
    }
}

jte {
    precompile()
}

kotlin {
    sourceSets["main"].kotlin.srcDir(generateVersionFile)
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

tasks.jar {
    dependsOn(tasks.precompileJte)
    from(fileTree("jte-classes") {
        include("**/*.class")
    })
}

tasks.run {
    dependsOn(tasks.precompileJte)
    workingDir = rootProject.projectDir
}

tasks.test {
    dependsOn(tasks.precompileJte)
}

tasks.shadowJar {
    isZip64 = true
}
