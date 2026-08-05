package uesugi.core.rule

import uesugi.common.toolkit.StrGlob
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

object RuleManager {

    private val lock = ReentrantReadWriteLock()

    @Volatile
    private var cachedRules: List<Rule> = emptyList()

    @Volatile
    private var initialized: Boolean = false

    /**
     * 确保规则已加载
     */
    private fun ensureInitialized() {
        if (!initialized) {
            lock.write {
                if (!initialized) {
                    cachedRules = RuleLoader.loadRules()
                    initialized = true
                }
            }
        }
    }

    /**
     * 获取对特定 bot+group 生效的所有规则
     * 包括：
     * - 所有 global=true 的规则
     * - botId 匹配且 groupId 为 null 的规则（bot 级别规则）
     * - botId 匹配且 groupId 通过 strGlob 匹配的规则（群组级别规则）
     */
    fun getRulesFor(botId: String, groupId: String): List<Rule> {
        ensureInitialized()
        return lock.read {
            cachedRules.filter { rule ->
                val meta = rule.meta
                when {
                    meta.global -> true
                    meta.botId == botId && meta.groupId == null -> true
                    meta.botId == botId && meta.groupId != null && StrGlob.matches(meta.groupId, groupId) -> true
                    else -> false
                }
            }
        }
    }

    /**
     * 获取仅属于特定 bot+group 的规则
     * botId 精确匹配，groupId 使用 strGlob 通配符匹配，供 ToolSet 使用
     */
    fun getRulesForBotGroup(botId: String, groupId: String): List<Rule> {
        ensureInitialized()
        return lock.read {
            cachedRules.filter { rule ->
                rule.meta.botId == botId && rule.meta.groupId != null && StrGlob.matches(rule.meta.groupId, groupId)
            }
        }
    }

    /**
     * 保存/创建规则文件到文件系统规则目录
     * 写入 Frontmatter + 正文
     */
    fun saveRule(fileName: String, meta: RuleMeta, content: String) {
        val rulesDir = RuleLoader.getRulesDirectory()
        val file = ensureSafeRuleFile(rulesDir, fileName)

        val fileContent = buildString {
            appendLine("---")
            if (meta.global) {
                appendLine("global: true")
            }
            meta.botId?.let { appendLine("botId: \"$it\"") }
            meta.groupId?.let { appendLine("groupId: \"$it\"") }
            appendLine("---")
            append(content)
        }

        file.writeText(fileContent)
        reload()
    }

    /**
     * 删除指定的规则文件
     * 仅限文件系统中的，且必须匹配 botId+groupId
     * @return 是否删除成功
     */
    fun deleteRule(fileName: String, botId: String, groupId: String): Boolean {
        ensureInitialized()

        // 文件名安全校验
        val safeName = sanitizeFileName(fileName)
        val targetFileName = ensureMdExtension(safeName)

        val ruleToDelete = lock.read {
            cachedRules.find { rule ->
                rule.fileName == targetFileName &&
                        rule.meta.botId == botId &&
                        rule.meta.groupId != null && StrGlob.matches(rule.meta.groupId, groupId) &&
                        !rule.filePath.startsWith("classpath:")
            }
        } ?: return false

        // 路径安全校验
        val rulesDir = RuleLoader.getRulesDirectory()
        val file = File(ruleToDelete.filePath)
        val canonicalRulesDir = rulesDir.canonicalFile
        val canonicalFile = file.canonicalFile
        require(
            canonicalFile.path.startsWith(canonicalRulesDir.path + File.separator) ||
                    canonicalFile.path == canonicalRulesDir.path
        ) {
            "非法文件路径：不在规则目录内"
        }

        val deleted = file.exists() && file.delete()

        if (deleted) {
            reload()
        }

        return deleted
    }

    /**
     * 重新加载所有规则刷新缓存
     */
    fun reload() {
        lock.write {
            cachedRules = RuleLoader.loadRules()
            initialized = true
        }
    }

    /**
     * 获取所有已加载的规则（用于调试/管理）
     */
    fun getAllRules(): List<Rule> {
        ensureInitialized()
        return lock.read { cachedRules.toList() }
    }

    /**
     * 确保文件名以 .md 结尾
     */
    private fun ensureMdExtension(fileName: String): String {
        return if (fileName.endsWith(".md")) fileName else "$fileName.md"
    }

    /**
     * 清洗文件名，防止路径遍历攻击
     */
    private fun sanitizeFileName(raw: String): String {
        val name = raw.trim()
        require(name.isNotEmpty()) { "文件名不能为空" }
        require(!name.contains('/') && !name.contains('\\')) { "非法文件名：不能包含路径分隔符" }
        require(!name.contains("..")) { "非法文件名：不能包含\"..\"" }
        return name
    }

    /**
     * 确保目标文件在规则目录内，防止路径遍历
     */
    private fun ensureSafeRuleFile(rulesDir: File, fileName: String): File {
        val safeName = sanitizeFileName(fileName)
        val target = File(rulesDir, ensureMdExtension(safeName))
        val canonicalRulesDir = rulesDir.canonicalFile
        val canonicalTarget = target.canonicalFile
        require(
            canonicalTarget.path.startsWith(canonicalRulesDir.path + File.separator) ||
                    canonicalTarget.path == canonicalRulesDir.path
        ) {
            "非法文件路径：不在规则目录内"
        }
        return canonicalTarget
    }
}
