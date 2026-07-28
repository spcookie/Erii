package uesugi.core.state.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.lucene.analysis.CharArraySet
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer
import org.apache.lucene.analysis.en.EnglishAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.use

data class FactEntityRebuildSummary(
    val scanned: Int,
    val updated: Int,
    val unchanged: Int,
    val failed: Int
)

data class FactEntityRebuildItem(
    val factId: Int,
    val keyword: String,
    val before: List<String>,
    val after: List<String>,
    val updated: Boolean,
    val error: String? = null
)

data class FactEntityRebuildReport(
    val summary: FactEntityRebuildSummary,
    val items: List<FactEntityRebuildItem>
)

class FactEntityRebuildRunner(
    private val repository: MemoryRepository,
    private val analyzer: suspend (FactsRecord) -> List<String>
) {
    suspend fun run(): FactEntityRebuildReport {
        val facts = withContext(Dispatchers.IO) {
            repository.getFactsForEntityRebuild(
                onlyEmptyEntities = true,
                includeInvalid = false
            )
        }

        val items = facts.map { fact ->
            try {
                val normalized = normalizeEntities(analyzer(fact))
                val changed = normalized != fact.entities
                if (changed) {
                    withContext(Dispatchers.IO) {
                        repository.updateFactEntities(fact.id, normalized)
                    }
                }
                FactEntityRebuildItem(
                    factId = fact.id,
                    keyword = fact.keyword,
                    before = fact.entities,
                    after = normalized,
                    updated = changed
                )
            } catch (e: Exception) {
                FactEntityRebuildItem(
                    factId = fact.id,
                    keyword = fact.keyword,
                    before = fact.entities,
                    after = fact.entities,
                    updated = false,
                    error = e.message ?: e::class.simpleName
                )
            }
        }

        val summary = FactEntityRebuildSummary(
            scanned = items.size,
            updated = items.count { it.updated },
            unchanged = items.count { it.error == null && !it.updated },
            failed = items.count { it.error != null }
        )
        return FactEntityRebuildReport(summary, items)
    }

    private fun normalizeEntities(values: List<String>): List<String> =
        values.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}

internal fun extractEntityCandidates(text: String): List<String> {
    if (text.isBlank()) return emptyList()

    return SmartChineseAnalyzer(entityAnalyzerStopWords).use { analyzer ->
        analyzer.tokenStream("entities", StringReader(text)).use { tokenStream ->
            val term = tokenStream.addAttribute(CharTermAttribute::class.java)
            val candidates = linkedSetOf<String>()
            tokenStream.reset()
            while (tokenStream.incrementToken() && candidates.size < 16) {
                term.toString().trim()
                    .takeIf { it.length >= 2 }
                    ?.let(candidates::add)
            }
            tokenStream.end()
            candidates.toList()
        }
    }
}

private val entityAnalyzerStopWords: CharArraySet =
    CharArraySet.copy(SmartChineseAnalyzer.getDefaultStopSet()).apply {
        addAll(EnglishAnalyzer.getDefaultStopSet())
    }

internal fun removeOrphanStoreDirectories(
    root: Path,
    activeKeys: Set<String>,
    closeStore: (String) -> Unit
): List<String> {
    if (!Files.isDirectory(root)) return emptyList()

    val orphanPaths = Files.list(root).use { paths ->
        paths.filter(Files::isDirectory)
            .filter { it.fileName.toString() !in activeKeys }
            .sorted()
            .toList()
    }
    return orphanPaths.map { path ->
        val key = path.fileName.toString()
        closeStore(key)
        check(path.toFile().deleteRecursively()) { "Failed to remove orphan store: $path" }
        key
    }
}
