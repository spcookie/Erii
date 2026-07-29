package uesugi.cli

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles

fun red(text: String) = TextColors.red(text)
fun green(text: String) = TextColors.green(text)
fun yellow(text: String) = TextColors.yellow(text)
fun blue(text: String) = TextColors.blue(text)
fun cyan(text: String) = TextColors.cyan(text)
fun gray(text: String) = TextColors.gray(text)
fun bold(text: String) = TextStyles.bold(text)

fun gradientText(text: String, startHex: String, endHex: String): String {
    val start = parseHex(startHex)
    val end = parseHex(endHex)
    val chars = text.toCharArray()
    val nonSpaceCount = chars.count { it != ' ' }
    if (nonSpaceCount <= 1) return text

    var processed = 0
    return chars.joinToString("") { char ->
        if (char == ' ') {
            char.toString()
        } else {
            val ratio = processed.toDouble() / (nonSpaceCount - 1)
            val r = (start.first + (end.first - start.first) * ratio).toInt()
            val g = (start.second + (end.second - start.second) * ratio).toInt()
            val b = (start.third + (end.third - start.third) * ratio).toInt()
            processed++
            TextColors.rgb(r / 255.0, g / 255.0, b / 255.0)(char.toString())
        }
    }
}

private fun parseHex(hex: String): Triple<Int, Int, Int> {
    val cleaned = hex.removePrefix("#")
    val r = cleaned.substring(0, 2).toInt(16)
    val g = cleaned.substring(2, 4).toInt(16)
    val b = cleaned.substring(4, 6).toInt(16)
    return Triple(r, g, b)
}
