package uesugi.spi.annotation

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Passive(
    val toolSets: Array<String> = ["default"],
    val onLoad: Array<String> = [],
    val onUnload: Array<String> = [],
    val description: String = "",
)
