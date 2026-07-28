package uesugi.core.state.meme

data class MemeRebuildOptions(
    val vector: Boolean = false
) {
    companion object {
        private const val VECTOR_PROPERTY = "meme.rebuild.vector"
        private const val VECTOR_ENV = "MEME_REBUILD_VECTOR"

        fun from(
            env: Map<String, String> = System.getenv(),
            property: (String) -> String? = System::getProperty
        ): MemeRebuildOptions = MemeRebuildOptions(
            vector = parseFlag(property(VECTOR_PROPERTY) ?: env[VECTOR_ENV])
        )

        private fun parseFlag(raw: String?): Boolean =
            raw?.trim()?.lowercase() in setOf("1", "true", "yes", "y", "on")
    }
}

data class MemeVectorRebuildResult(
    val memes: Int,
    val groups: List<String>
)
