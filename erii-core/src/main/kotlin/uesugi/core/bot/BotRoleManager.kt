package uesugi.core.bot

import org.yaml.snakeyaml.Yaml
import uesugi.common.BotRole
import uesugi.common.ConfigBotRole
import uesugi.common.data.EmotionalTendencies
import uesugi.common.toolkit.logger
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

/**
 * BotRole 管理器，负责从配置文件动态加载角色
 */
object BotRoleManager {
    private val roles = ConcurrentHashMap<String, BotRole>()
    private val log = logger()
    private const val DEFAULT_SOULS_DIR = "souls"

    /**
     * 加载所有角色配置
     * 加载顺序：
     * 1. 先加载 classpath 下 souls/index.idx 索引指定的角色配置
     * 2. 再加载配置目录，覆盖默认配置
     *
     * @param configDir 配置文件目录，支持：
     *  - 系统属性 -Dconfig.souls.dir
     *  - 环境变量 CONFIG_SOULS_DIR
     *  - 默认值 "souls"
     */
    fun loadRoles(configDir: String? = null) {
        // 1. 先加载 classpath 下 souls/index.idx 索引指定的角色配置
        loadFromClasspathIndex()

        // 2. 加载自定义配置目录
        val customDir = resolveConfigDir(configDir)
        log.info("Start loading BotRole from custom configuration directory, directory: $customDir")
        loadFromDirectory(File(customDir))

        log.info("BotRole loading completed, with a total of ${roles.size} configurations loaded: ${roles.keys}")
    }

    private fun resolveConfigDir(configDir: String?): String {
        val raw = configDir
            ?: System.getProperty("config.souls.dir")
            ?: System.getenv("CONFIG_SOULS_DIR")
            ?: DEFAULT_SOULS_DIR
        return File(raw).toPath().toAbsolutePath().toString()
    }

    private fun loadFromDirectory(dir: File) {
        dir.listFiles { file -> file.extension == "md" }?.forEach { file ->
            try {
                val content = file.readText()
                val role = parseMarkdown(content, file.nameWithoutExtension)
                if (role != null) {
                    roles[role.id] = role
                    log.info("Loaded BotRole from directory: ${role.id} (${role.name})")
                }
            } catch (e: Exception) {
                log.error("Failed to load BotRole configuration file: ${file.name}", e)
            }
        }
    }

    private fun loadFromClasspath() {
        val classLoader = BotRoleManager::class.java.classLoader
        val resources = classLoader.getResources(DEFAULT_SOULS_DIR)
        while (resources.hasMoreElements()) {
            val resource = resources.nextElement()
            try {
                val jarUrl = resource.toString()
                // 获取 JAR 文件并列出条目
                if (jarUrl.startsWith("jar:")) {
                    val jarFileUrl = jarUrl.substring(4, jarUrl.indexOf("!")).removePrefix("file:")
                    val jarFile = JarFile(URLDecoder.decode(jarFileUrl, "UTF-8"))
                    val entries = jarFile.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val entryName = entry.name
                        // 检查是否在 botroles 目录下且是 .md 文件
                        if (entryName.startsWith(DEFAULT_SOULS_DIR) && entryName.endsWith(".md") && !entry.isDirectory) {
                            val fileName = entryName.substring(entryName.lastIndexOf("/") + 1)
                            val inputStream = jarFile.getInputStream(entry)
                            inputStream.use { inputStream ->
                                val content = inputStream.bufferedReader().readText()
                                val role = parseMarkdown(content, fileName.removeSuffix(".md"))
                                if (role != null) {
                                    roles[role.id] = role
                                    log.info("Load BotRole from the classpath file: ${role.id} (${role.name})")
                                }
                            }
                        }
                    }
                    jarFile.close()
                }
            } catch (e: Exception) {
                log.error("Failed to read BotRole resource: $resource", e)
            }
        }
    }

    /**
     * 从 classpath 索引文件加载角色配置
     * 读取 souls/index.idx 索引文件，按索引加载指定的 md 文件
     */
    private fun loadFromClasspathIndex() {
        val classLoader = BotRoleManager::class.java.classLoader
        val indexResource = classLoader.getResource("$DEFAULT_SOULS_DIR/index.idx")
        if (indexResource == null) {
            log.warn("BotRole index file does not exist: $DEFAULT_SOULS_DIR/index.idx，attempt to load from classpath directly")
            loadFromClasspath()
            return
        }

        try {
            val indexContent = indexResource.content as? String
                ?: indexResource.openStream().bufferedReader().readText()
            val fileNames = indexContent.lines().map { it.trim() }.filter { it.isNotEmpty() && it.endsWith(".md") }

            log.info("Load BotRole from index file, index file: $DEFAULT_SOULS_DIR/index.idx，include ${fileNames.size} files")

            for (fileName in fileNames) {
                val resourcePath = "$DEFAULT_SOULS_DIR/$fileName"
                val resource = classLoader.getResource(resourcePath)
                if (resource != null) {
                    try {
                        val content = resource.openStream().bufferedReader().readText()
                        val role = parseMarkdown(content, fileName.removeSuffix(".md"))
                        if (role != null) {
                            roles[role.id] = role
                            log.info("Load BotRole from index: ${role.id} (${role.name})")
                        }
                    } catch (e: Exception) {
                        log.error("Loading BotRole failed: $resourcePath", e)
                    }
                } else {
                    log.warn(" BotRole file specified in index does not exist: $resourcePath")
                }
            }
        } catch (e: Exception) {
            log.error("Failed to read BotRole index file: $DEFAULT_SOULS_DIR/index.idx", e)
            loadFromClasspath()
        }
    }

    /**
     * 解析 Markdown + Front Matter 格式的配置文件
     */
    private fun parseMarkdown(content: String, defaultId: String): BotRole? {
        // 分离 Front Matter 和正文
        val fmRegex = Regex(
            "^---\\s*\\R([\\s\\S]*?)\\R---\\s*\\R([\\s\\S]*)$",
            RegexOption.MULTILINE
        )
        val match = fmRegex.find(content) ?: return null

        val frontMatter = match.groupValues[1]
        val body = match.groupValues[2].trim()

        // 解析 YAML
        val yaml = Yaml()

        @Suppress("UNCHECKED_CAST")
        val data = yaml.load<Map<String, Any>>(frontMatter)

        val id = (data["id"] as? String) ?: defaultId
        val name = (data["name"] as? String) ?: id
        val character = (data["character"] as? String) ?: ""
        val emoticonName = (data["emoticon"] as? String)?.uppercase()
        val emoticon = try {
            emoticonName?.let { EmotionalTendencies.valueOf(it) } ?: EmotionalTendencies.MILDNESS
        } catch (_: IllegalArgumentException) {
            log.warn("Unknown EmotionalTendencies: $emoticonName, Use default values MILDNESS")
            EmotionalTendencies.MILDNESS
        }

        // 组装 personality，将 body 作为人格描述的一部分
        val personality = """
            |你的名字叫 $name。
            |你的ID是：{{botId}}。
            |你不是聊天机器人。你正在成为某个人。
            |
            |$body
            |
            |## 话题偏好
            |
            |$character
        """.trimMargin()

        return ConfigBotRole(
            id = id,
            name = name,
            personalityTemplate = personality,
            character = character,
            emoticon = emoticon
        )
    }

    /**
     * 获取指定 ID 的角色
     */
    fun getRole(id: String): BotRole? = roles[id]

    /**
     * 获取所有已加载的角色
     */
    fun getAllRoles(): Map<String, BotRole> = roles.toMap()

    /**
     * 获取默认角色
     */
    fun getDefaultRole(): BotRole = roles["Erii"]
        ?: throw IllegalStateException("Default BotRole (Erii) not configured")

    /**
     * 重新加载角色配置
     */
    fun reload(configDir: String? = null) {
        roles.clear()
        loadRoles(configDir)
    }
}