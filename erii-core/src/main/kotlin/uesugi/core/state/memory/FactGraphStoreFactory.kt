package uesugi.core.state.memory

import org.koin.core.context.GlobalContext
import org.koin.core.parameter.parametersOf
import uesugi.config.StorePathConfig
import uesugi.core.component.storage.GraphStore

/**
 * 图存储工厂，按 botMark + groupId 管理多实例 GraphStore，封装存储操作。
 */
open class FactGraphStoreFactory {

    private val stores = mutableMapOf<String, GraphStore>()

    open fun getStore(botMark: String, groupId: String): GraphStore {
        val key = "${botMark}_$groupId"
        return stores.getOrPut(key) {
            val path = StorePathConfig.resolve("graph", "fact", key)
            GlobalContext.get().get { parametersOf(path, botMark, groupId) }
        }
    }

    open fun addFactEntities(fact: FactsRecord) {
        getStore(fact.botMark, fact.groupId).addFactEntities(fact)
    }

    open fun removeFactEntities(factId: Int, botMark: String, groupId: String) {
        getStore(botMark, groupId).removeFactEntities(factId)
    }

    open fun rebuildStore(botMark: String, groupId: String) {
        getStore(botMark, groupId).rebuild()
    }

    open fun removeOrphanStores(activeGroups: List<Pair<String, String>>): List<String> {
        val root = StorePathConfig.resolve("graph", "fact")
        val activeKeys = activeGroups.mapTo(hashSetOf()) { (botMark, groupId) -> "${botMark}_$groupId" }
        return removeOrphanStoreDirectories(root, activeKeys) { key ->
            stores.remove(key)?.close()
        }
    }

    open fun expandByEntities(entityIds: List<String>, botMark: String, groupId: String): List<Int> {
        return getStore(botMark, groupId).expandByEntities(entityIds)
    }

    open fun expandByFacts(factIds: List<Int>, botMark: String, groupId: String): List<String> {
        return getStore(botMark, groupId).expandByFacts(factIds)
    }
}
