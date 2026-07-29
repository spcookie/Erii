package uesugi.common.toolkit

object StrGlob {

    fun matches(pattern: String, text: String): Boolean =
        matchesInternal(pattern, text, ignoreCase = false)

    fun matchesIgnoreCase(pattern: String, text: String): Boolean =
        matchesInternal(pattern, text, ignoreCase = true)

    private fun matchesInternal(
        pattern: String,
        text: String,
        ignoreCase: Boolean
    ): Boolean {
        var patternIndex = 0
        var textIndex = 0
        var starIndex = -1
        var starMatchIndex = -1

        while (textIndex < text.length) {
            when {
                patternIndex < pattern.length &&
                        (
                                pattern[patternIndex] == '?' ||
                                        pattern[patternIndex].equals(
                                            text[textIndex],
                                            ignoreCase
                                        )
                                ) -> {
                    patternIndex++
                    textIndex++
                }

                patternIndex < pattern.length &&
                        pattern[patternIndex] == '*' -> {
                    starIndex = patternIndex++
                    starMatchIndex = textIndex
                }

                starIndex >= 0 -> {
                    patternIndex = starIndex + 1
                    textIndex = ++starMatchIndex
                }

                else -> return false
            }
        }

        while (
            patternIndex < pattern.length &&
            pattern[patternIndex] == '*'
        ) {
            patternIndex++
        }

        return patternIndex == pattern.length
    }
}