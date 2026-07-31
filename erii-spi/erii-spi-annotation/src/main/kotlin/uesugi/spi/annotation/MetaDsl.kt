package uesugi.spi.annotation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import uesugi.spi.Meta

suspend fun useMeta(): Meta = currentCoroutineContext()[MetaElement]?.meta
    ?: error(NO_META_ERROR)

suspend fun useToolMeta(): Lazy<Meta> = lazyOf(useMeta())

suspend fun <T> withMeta(meta: Meta, block: suspend () -> T): T {
    return withContext(MetaElement(meta)) {
        block()
    }
}

suspend fun <T> withMetaIO(meta: Meta, block: suspend () -> T): T {
    return withContext(Dispatchers.IO + MetaElement(meta)) {
        block()
    }
}
