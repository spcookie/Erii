package uesugi.config

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import uesugi.common.data.HistoryTable
import uesugi.common.data.ResourceTable
import uesugi.core.component.usage.TokenUsageTable
import uesugi.core.state.emotion.EmotionTable
import uesugi.core.state.evolution.EvolutionStateTable
import uesugi.core.state.evolution.LearnedVocabTable
import uesugi.core.state.flow.FlowStateTable
import uesugi.core.state.meme.MemeData.MemeScanStateTable
import uesugi.core.state.meme.MemeData.MemeTable
import uesugi.core.state.memory.FactsTable
import uesugi.core.state.memory.MemoryStateTable
import uesugi.core.state.memory.UserProfileTable
import uesugi.core.state.summary.SummaryStateTable
import uesugi.core.state.summary.SummaryTable
import uesugi.core.state.volition.VolitionStateTable

private val log = KotlinLogging.logger {}

fun migration(database: Database) {
    transaction(database) {
        // 将 bot_mark 列重命名为 bot_id（兼容旧数据库）
        listOf(
            "chat_history", "chat_resource", "chat_emotion",
            "memory_facts", "memory_user_profile", "memory_state",
            "memory_summary", "summary_state",
            "learned_vocab", "evolution_state",
            "flow_state", "volition_state",
            "meme", "meme_scan_state"
        ).forEach { table ->
            try {
                exec("ALTER TABLE $table ALTER COLUMN bot_mark RENAME TO bot_id")
            } catch (_: ExposedSQLException) {
                // ignore
            }
        }

        val migration = MigrationUtils.statementsRequiredForDatabaseMigration(
            HistoryTable,
            ResourceTable,
            EmotionTable,
            FactsTable,
            UserProfileTable,
            SummaryTable,
            SummaryStateTable,
            MemoryStateTable,
            LearnedVocabTable,
            EvolutionStateTable,
            FlowStateTable,
            VolitionStateTable,
            MemeTable,
            MemeScanStateTable,
            TokenUsageTable
        )
        migration.forEach { statement ->
            try {
                exec(statement)
            } catch (e: ExposedSQLException) {
                val message = e.cause?.message ?: e.message ?: ""
                // H2 90085: 索引属于某个约束，不能直接删除（例如外键自动创建的索引）
                if (statement.trimStart().startsWith("DROP INDEX", ignoreCase = true) &&
                    (message.contains("belongs to constraint") || message.contains("90085"))
                ) {
                    log.warn(e) { "跳过无法删除的约束索引: $statement" }
                } else {
                    throw e
                }
            }
        }

        // 兼容旧版 facts 表：旧列使用了带引号的小写名称 "values"。
        // H2 对带引号标识符区分大小写，未加引号的 DROP values 实际查找的是 VALUES，无法删除旧列。
        exec("ALTER TABLE memory_facts ADD COLUMN IF NOT EXISTS entities TEXT DEFAULT '[]' NOT NULL")
        exec("ALTER TABLE memory_facts DROP COLUMN IF EXISTS \"values\"")
    }
}

private fun init(database: Database) {
    transaction(database) {
        SchemaUtils.create(
            HistoryTable,
            ResourceTable,
            EmotionTable,
            FactsTable,
            UserProfileTable,
            SummaryTable,
            SummaryStateTable,
            MemoryStateTable,
            LearnedVocabTable,
            EvolutionStateTable,
            FlowStateTable,
            VolitionStateTable,
            MemeTable,
            MemeScanStateTable,
            TokenUsageTable,
            inBatch = true
        )
    }
}

fun migrationIf(condition: Boolean, database: Database) {
    init(database)
    if (condition) {
        migration(database)
    }
}
