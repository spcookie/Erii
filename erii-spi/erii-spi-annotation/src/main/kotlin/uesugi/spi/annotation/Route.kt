package uesugi.spi.annotation

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Route(
    val desc: String,
    val key: String,
    val toolSets: Array<String> = ["default"],
    val onLoad: Array<String> = [],
    val onUnload: Array<String> = [],
    val description: String = "",
)
