package uesugi.core.state.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val words = Regex("""[\p{IsHan}A-Za-z0-9_/\-.]{2,}""")
        .findAll(text)
        .map { it.value.trim() }
        .filterNot { it in entityStopWords }
        .toList()
    return words.distinct().take(16)
}

private val entityStopWords = setOf(
    "user",
    "from",
    "to",
    "and",
    "the",
    "已经",
    "开始",
    "用户",
    "事实"
)
