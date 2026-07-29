package uesugi.spi.annotation

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Cmd(
    val name: String,
    val alias: Array<String> = [],
    val toolSets: Array<String> = ["default"],
    val onLoad: Array<String> = [],
    val onUnload: Array<String> = [],
    val description: String = "",
)
