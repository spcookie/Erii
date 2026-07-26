package uesugi.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Zip
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.plugin.KaptExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapper

class EriiGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (project.group.toString().isEmpty()) {
            project.group = "uesugi.plugin"
        }

        applyKotlinPlugins(project)
        addDependencies(project)
        registerTasks(project)

        project.afterEvaluate {
            configureKotlin(project)
            configureKapt(project)
        }
    }

    // --- Kotlin plugins ---

    private fun applyKotlinPlugins(project: Project) {
        project.pluginManager.apply(KotlinPluginWrapper::class.java)

        for (className in KAPT_SERIALIZATION_PLUGIN_CLASSES) {
            try {
                @Suppress("UNCHECKED_CAST")
                val pluginClass = Class.forName(className) as Class<Plugin<Project>>
                project.pluginManager.apply(pluginClass)
            } catch (_: Exception) {
            }
        }

        try {
            @Suppress("UNCHECKED_CAST")
            val kspClass = Class.forName(KSP_PLUGIN_CLASS) as Class<Plugin<Project>>
            project.pluginManager.apply(kspClass)
        } catch (_: Exception) {
        }
    }

    private fun configureKotlin(project: Project) {
        val kotlin = project.extensions.getByType(KotlinProjectExtension::class.java)
        kotlin.jvmToolchain(17)
    }

    private fun configureKapt(project: Project) {
        val kapt = project.extensions.getByType(KaptExtension::class.java)
        kapt.arguments {
            arg("plugin.id", project.name)
            arg("plugin.version", project.version.toString())
            arg(
                "kapt.kotlin.generated",
                project.layout.buildDirectory.dir("generated/source/kaptKotlin/main").get().asFile.absolutePath
            )
        }
    }

    // --- Dependencies ---

    private fun addDependencies(project: Project) {
        project.dependencies.apply {
            for (dep in COMPILE_ONLY_DEPS) {
                add("compileOnly", dep)
            }
            for (dep in KAPT_DEPS) {
                add("kapt", dep)
            }
            for (dep in KSP_DEPS) {
                add("ksp", dep)
            }
        }
    }

    // --- Tasks ---

    private fun registerTasks(project: Project) {
        val pluginsDir = project.layout.buildDirectory.dir("plugin")

        val pluginZip = project.tasks.register("pluginZip", Zip::class.java) { zip ->
            zip.archiveBaseName.set(project.name)
            zip.archiveExtension.set("zip")

            val mainSources = project.extensions.getByType(SourceSetContainer::class.java)
                .getByName("main")

            zip.from(
                mainSources.output.asFileTree.matching { it.include("plugin.properties") }
            )

            zip.into("classes") {
                val jarTask = project.tasks.named("jar", Jar::class.java)
                it.from(
                    jarTask.flatMap { t -> t.archiveFile }
                        .map { f -> project.zipTree(f) }
                ) { copy -> copy.exclude("plugin.properties") }
            }

            zip.into("lib") {
                it.from(
                    project.configurations.getByName("runtimeClasspath")
                        .resolvedConfiguration
                        .resolvedArtifacts
                        .filter { a -> a.moduleVersion.id.group !in KOTLIN_GROUPS }
                        .map { a -> a.file }
                )
            }
        }

        val assemblePlugin = project.tasks.register("assemblePlugin", Copy::class.java) { copy ->
            copy.from(pluginZip)
            copy.from(project.file("README.md"))
            copy.into(pluginsDir)
        }

        project.tasks.named("build") { it.dependsOn(assemblePlugin) }

        // kspKotlin / kaptKotlin 是通过反射注册的 task，不在显式依赖链上时
        // 可能被 Gradle 跳过，导致 pluginZip 打包空内容。这里强制挂载依赖。
        project.afterEvaluate {
            val kspTask = project.tasks.findByName("kspKotlin")
            val kaptTask = project.tasks.findByName("kaptKotlin")
            if (kspTask != null || kaptTask != null) {
                pluginZip.configure {
                    if (kspTask != null) it.dependsOn(kspTask)
                    if (kaptTask != null) it.dependsOn(kaptTask)
                }
            }
        }
    }

    companion object {
        private val KAPT_SERIALIZATION_PLUGIN_CLASSES = listOf(
            "org.jetbrains.kotlin.gradle.internal.Kapt3GradleSubplugin",
            "org.jetbrains.kotlinx.serialization.gradle.SerializationGradleSubplugin",
        )

        private const val KSP_PLUGIN_CLASS = "com.google.devtools.ksp.gradle.KspGradleSubplugin"

        private val KOTLIN_GROUPS = setOf("org.jetbrains.kotlin", "org.jetbrains.kotlinx", "org.jetbrains")

        private val COMPILE_ONLY_DEPS = listOf(
            "uesugi:erii-spi-core:1.0.0",
            "uesugi:erii-spi-annotation:1.0.0",
            "uesugi:erii-common:1.0.0",
            "uesugi:onebot-sdk:1.0.0",
            "org.jobrunr:jobrunr:8.3.1",
            "com.github.ajalt.clikt:clikt:5.1.0",
            "ai.koog:koog-agents:1.0.0",
            "org.jetbrains.exposed:exposed-core:1.3.0",
            "org.jetbrains.exposed:exposed-jdbc:1.3.0",
            "org.jetbrains.exposed:exposed-json:1.3.0",
            "org.jetbrains.exposed:exposed-kotlin-datetime:1.3.0",
            "org.jetbrains.exposed:exposed-migration-core:1.3.0",
            "org.jetbrains.exposed:exposed-migration-jdbc:1.3.0",
            "io.ktor:ktor-server-core:3.3.2",
            "io.ktor:ktor-client-core:3.3.2",
            "com.typesafe:config:1.4.0",
            "org.pf4j:pf4j:3.15.0",
            "com.squareup.okio:okio-jvm:3.6.0",
            "io.ktor:ktor-serialization-jackson:3.3.2",
            "org.jetbrains.kotlinx:atomicfu:0.23.2",
            "io.arrow-kt:arrow-core:2.0.0",
            "io.github.oshai:kotlin-logging-jvm:8.0.02",
        )

        private val KAPT_DEPS = listOf(
            "uesugi:erii-spi-core:1.0.0",
            "org.pf4j:pf4j:3.15.0",
        )

        private val KSP_DEPS = listOf(
            "uesugi:erii-spi-annotation:1.0.0",
        )
    }
}
