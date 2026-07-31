package uesugi.plugin

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import okio.buffer
import org.jetbrains.exposed.v1.core.Schema
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uesugi.common.data.HistoryRecord
import uesugi.common.data.HistoryTable
import uesugi.common.data.ResourceRecord
import uesugi.common.data.ResourceTable
import uesugi.common.toolkit.ref
import uesugi.config.StorePathConfig
import uesugi.core.component.storage.ObjectStorage
import javax.sql.DataSource
import uesugi.spi.Database as SpiDatabase

internal class PluginDatabaseImpl(private val pluginName: String) : SpiDatabase {

    private val dataSource: DataSource by lazy {
        val config = HikariConfig().apply {
            jdbcUrl = StorePathConfig.h2JdbcUrl("MODE=PostgreSQL;AUTO_SERVER=TRUE;NON_KEYWORDS=VALUE")
            driverClassName = "org.h2.Driver"
            maximumPoolSize = 2
            poolName = "plugin-$pluginName"
        }
        HikariDataSource(config)
    }

    private val exposedDb: Database by lazy {
        Database.connect(dataSource)
    }

    private val schema by lazy { Schema(pluginName) }

    private val schemaCreated = atomic(false)

    override suspend fun <T> execute(block: () -> T): T = withContext(Dispatchers.IO) {
        transaction(exposedDb) {
            if (schemaCreated.compareAndSet(false, true)) {
                SchemaUtils.createSchema(schema)
            }
            exec("SET SCHEMA \"$pluginName\"")
            block()
        }
    }

    override suspend fun getHistory(query: () -> Query): List<HistoryRecord> {
        val storage by ref<ObjectStorage>()
        return withContext(Dispatchers.IO) {
            transaction {
                query().map {
                    HistoryRecord(
                        id = it[HistoryTable.id].value,
                        botId = it[HistoryTable.botId],
                        groupId = it[HistoryTable.groupId],
                        userId = it[HistoryTable.userId],
                        nick = it[HistoryTable.nick],
                        messageType = it[HistoryTable.messageType],
                        content = it[HistoryTable.content],
                        resource = if (it.getOrNull(ResourceTable.id) != null) {
                            val url = it[ResourceTable.url]
                            val bytes = storage.get(url.toPath()).use { source -> source.buffer().readByteArray() }
                            ResourceRecord(
                                id = it[ResourceTable.id].value,
                                botId = it[ResourceTable.botId],
                                groupId = it[ResourceTable.groupId],
                                url = url,
                                fileName = it[ResourceTable.fileName],
                                size = it[ResourceTable.size],
                                md5 = it[ResourceTable.md5],
                                createdAt = it[ResourceTable.createdAt],
                                bytes = bytes
                            )
                        } else {
                            null
                        },
                        createdAt = it[HistoryTable.createdAt]
                    )
                }
            }
        }
    }
}
