package uesugi.core.component.storage

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VectorStoreTest {
    @Test
    fun `invalid rebuild leaves committed index unchanged`() {
        val path = Files.createTempDirectory("erii-vector-store-rebuild-test")
        val store = EmbeddedVectorStore(path, 4)
        try {
            store.upsert("existing", "existing content", "GROUP", floatArrayOf(1f, 0f, 0f, 0f))
            store.flush()

            assertFailsWith<IllegalArgumentException> {
                store.rebuild(
                    listOf(
                        VectorStoreItem("invalid", "invalid", "GROUP", floatArrayOf(1f, 0f))
                    )
                )
            }

            assertEquals("existing", store.search(floatArrayOf(1f, 0f, 0f, 0f), 10).single().id)
        } finally {
            store.close()
            path.toFile().deleteRecursively()
        }
    }

    @Test
    fun `text search uses search text while returning stored content`() {
        val path = Files.createTempDirectory("erii-vector-store-test")
        val store = EmbeddedVectorStore(path, 4)
        try {
            store.upsert(
                id = "fact-1",
                content = "description only",
                tag = "GROUP",
                vector = floatArrayOf(1f, 0f, 0f, 0f),
                searchText = "keyword 杭州 entity"
            )
            store.upsert(
                id = "fact-2",
                content = "other description",
                tag = "GROUP",
                vector = floatArrayOf(0f, 1f, 0f, 0f),
                searchText = "重庆"
            )

            val results = store.searchText("杭州", 10)

            assertEquals(listOf("fact-1"), results.map { it.id })
            assertEquals("description only", results.single().content)
        } finally {
            store.close()
            path.toFile().deleteRecursively()
        }
    }
}
