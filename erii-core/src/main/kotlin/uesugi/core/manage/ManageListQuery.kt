package uesugi.core.manage

import org.jetbrains.exposed.v1.core.SortOrder

data class ManageListQuery(
    val offset: Int = 0,
    val limit: Int = 50,
    val search: String = "",
    val sortBy: String = "",
    val ascending: Boolean = false
) {
    val sortOrder: SortOrder
        get() = if (ascending) SortOrder.ASC else SortOrder.DESC

    val searchPattern: String
        get() = "%${search.trim().lowercase()}%"
}
