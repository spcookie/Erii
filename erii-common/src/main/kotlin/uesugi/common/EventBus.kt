package uesugi.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

class EventDispatchException(
    failures: List<Throwable>,
) : RuntimeException(
    "${failures.size} event subscriber(s) failed",
    failures.firstOrNull(),
) {
    init {
        failures.drop(1).forEach(::addSuppressed)
    }
}

@Suppress("UNCHECKED_CAST", "UNUSED")
object EventBus {

    /* ================= async ================= */

    @PublishedApi
    internal object AsyncBus {

        // sendReplay = 0 确保新订阅者不会收到旧消息
        private val bus = MutableSharedFlow<Any>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

        fun post(event: Any) {
            // 如果必须保证发送成功不丢失，可以使用 emit，但这里作为 EventBus，防止阻塞发送端通常更重要
            bus.tryEmit(event)
        }

        fun <T : Any> subscribe(
            kClass: KClass<T>,
            scope: CoroutineScope,
            once: Boolean = false,
            onEvent: suspend (T) -> Unit
        ): Job {
            val flow = bus
                .filter { kClass.isInstance(it) } // 先过滤类型
                .map { it as T }                  // 强转

            val targetFlow = if (once) flow.take(1) else flow

            return targetFlow
                .onEach {
                    try {
                        onEvent(it)
                    } catch (e: Exception) {
                        e.printStackTrace() // 防止消费者崩溃导致流终止
                    }
                }
                .launchIn(scope)
        }
    }

    /* ================= sync ================= */

    @PublishedApi
    internal object SyncBus {

        private data class Subscriber(
            val kClass: KClass<*>,
            val once: Boolean,
            val callback: (Any) -> Unit,
            val originalRef: Any // 修复核心 Bug：保存原始 lambda 引用用于对比
        )

        // CopyOnWriteArrayList 允许在遍历时无需加锁，性能更好且避免 ConcurrentModificationException
        private val subscribers = CopyOnWriteArrayList<Subscriber>()

        private fun postAndCollectFailures(event: Any): List<Throwable> {
            val failures = mutableListOf<Throwable>()
            subscribers.forEach { sub ->
                if (sub.kClass.isInstance(event)) {
                    try {
                        sub.callback(event)
                    } catch (failure: Exception) {
                        if (failure is InterruptedException || Thread.currentThread().isInterrupted) {
                            throw failure
                        }
                        failures += failure
                    } finally {
                        if (sub.once) {
                            subscribers.remove(sub)
                        }
                    }
                }
            }
            return failures
        }

        fun post(event: Any) {
            postAndCollectFailures(event).forEach(Throwable::printStackTrace)
        }

        fun postOrThrow(event: Any) {
            val failures = postAndCollectFailures(event)
            if (failures.isNotEmpty()) {
                throw EventDispatchException(failures)
            }
        }

        fun <T : Any> subscribe(
            kClass: KClass<T>,
            once: Boolean = false,
            onEvent: (T) -> Unit
        ) {
            val subscriber = Subscriber(
                kClass = kClass,
                once = once,
                callback = { event -> onEvent(event as T) },
                originalRef = onEvent // 保存原始引用
            )
            subscribers.add(subscriber)
        }

        fun <T : Any> unsubscribe(
            kClass: KClass<T>,
            onEvent: (T) -> Unit
        ) {
            // 修复：使用 originalRef 进行引用对比
            subscribers.removeIf {
                it.kClass == kClass && it.originalRef === onEvent
            }
        }
    }

    /* ================= public api ================= */

    fun postAsync(event: Any) = AsyncBus.post(event)

    inline fun <reified T : Any> subscribeAsync(
        scope: CoroutineScope,
        noinline onEvent: suspend (T) -> Unit
    ): Job =
        AsyncBus.subscribe(T::class, scope, once = false, onEvent)

    inline fun <reified T : Any> subscribeOnceAsync(
        scope: CoroutineScope,
        noinline onEvent: suspend (T) -> Unit
    ): Job =
        AsyncBus.subscribe(T::class, scope, once = true, onEvent)

    fun unsubscribeAsync(job: Job) = job.cancel()

    fun postSync(event: Any) = SyncBus.post(event)

    /** Dispatches to every matching synchronous subscriber and reports all subscriber failures to the caller. */
    fun postSyncOrThrow(event: Any) = SyncBus.postOrThrow(event)

    inline fun <reified T : Any> subscribeSync(
        noinline onEvent: (T) -> Unit
    ): (T) -> Unit {
        SyncBus.subscribe(T::class, once = false, onEvent)
        return onEvent
    }


    inline fun <reified T : Any> subscribeOnceSync(
        noinline onEvent: (T) -> Unit
    ) =
        SyncBus.subscribe(T::class, once = true, onEvent)

    inline fun <reified T : Any> unsubscribeSync(
        noinline onEvent: (T) -> Unit
    ) =
        SyncBus.unsubscribe(T::class, onEvent)
}
