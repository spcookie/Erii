package uesugi.core.state.memory

import org.koin.core.context.GlobalContext
import org.koin.core.parameter.parametersOf
import uesugi.config.StorePathConfig
import uesugi.core.component.storage.GraphStore

/**
 * 图存储工厂，按 botId + groupId 管理多实例 GraphStore，封装存储操作。
 */
open class FactGraphStoreFactory {

    private val stores = mutableMapOf<String, GraphStore>()

    open fun getStore(botId: String, groupId: String): GraphStore {
        val key = "${botId}_$groupId"
        return stores.getOrPut(key) {
            val path = StorePathConfig.resolve("graph", "fact", key)
            GlobalContext.get().get { parametersOf(path, botId, groupId) }
        }
    }

    open fun addFactEntities(fact: FactsRecord) {
        getStore(fact.botId, fact.groupId).addFactEntities(fact)
    }

    open fun removeFactEntities(factId: Int, botId: String, groupId: String) {
        getStore(botId, groupId).removeFactEntities(factId)
    }

    open fun rebuildStore(botId: String, groupId: String) {
        getStore(botId, groupId).rebuild()
    }

    open fun removeOrphanStores(activeGroups: List<Pair<String, String>>): List<String> {
        val root = StorePathConfig.resolve("graph", "fact")
        val activeKeys = activeGroups.mapTo(hashSetOf()) { (botId, groupId) -> "${botId}_$groupId" }
        return removeOrphanStoreDirectories(root, activeKeys) { key ->
            stores.remove(key)?.close()
        }
    }

    open fun expandByEntities(entityIds: List<String>, botId: String, groupId: String): List<Int> {
        return getStore(botId, groupId).expandByEntities(entityIds)
    }

    open fun expandByFacts(factIds: List<Int>, botId: String, groupId: String): List<String> {
        return getStore(botId, groupId).expandByFacts(factIds)
    }
}
