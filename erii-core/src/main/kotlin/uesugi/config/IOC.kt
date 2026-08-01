package uesugi.config

import io.ktor.server.application.*
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.koinModule
import uesugi.core.chat.chatModule
import uesugi.core.cleanup.ResourceCleanupJob
import uesugi.core.cleanup.cleanupModule
import uesugi.core.component.llm.provider.AnthropicClientProvider
import uesugi.core.component.llm.provider.OpenAIClientProvider
import uesugi.core.component.storage.*
import uesugi.core.component.usage.TokenUsageRepository
import uesugi.core.cron.CronService
import uesugi.core.cron.cronModule
import uesugi.core.message.messageModule
import uesugi.core.state.dispatch.StateDispatchJob
import uesugi.core.state.dispatch.stateDispatchModule
import uesugi.core.state.emotion.EmotionJob
import uesugi.core.state.emotion.emotionModule
import uesugi.core.state.evolution.EvolutionJob
import uesugi.core.state.evolution.evolutionModule
import uesugi.core.state.flow.FlowJob
import uesugi.core.state.flow.flowModule
import uesugi.core.state.meme.MemeJob
import uesugi.core.state.meme.memeModule
import uesugi.core.state.memory.MemoryJob
import uesugi.core.state.memory.MemoryRepository
import uesugi.core.state.memory.memoryModule
import uesugi.core.state.summary.SummaryJob
import uesugi.core.state.summary.summaryModule
import uesugi.core.state.volition.VolitionJob
import uesugi.core.state.volition.volitionModule
import javax.sql.DataSource

fun Application.warmUp() {
    monitor.subscribe(ApplicationStarted) {
        it.get<EmotionJob>().openTimingTriggerSignal()
        it.get<MemoryJob>().openTimingTriggerSignal()
        it.get<SummaryJob>().openTimingTriggerSignal()
        it.get<VolitionJob>().openTimingTriggerSignal()
        it.get<EvolutionJob>().openTimingTriggerSignal()
        it.get<FlowJob>().openTimingTriggerSignal()
        it.get<MemeJob>().openTimingTriggerSignal()
        it.get<ResourceCleanupJob>().openTimingTriggerSignal()
        it.get<StateDispatchJob>().open()
        it.get<CronService>().start()
    }
}

fun Application.configBaseModule() = koinModule {
    single<ObjectStorage> {
        LocalObjectStorage(
            baseDir = StorePathConfig.resolveOkio("object")
        )
    }
    factory<VectorStore> {
        EmbeddedVectorStore(it.get(), it.get())
    }
    factory<GraphStore> { params ->
        Rdf4jGraphStore(params.get(), params.get(), params.get(), get<MemoryRepository>())
    }
    single<DataSource> {
        ConnectionFactoryConfig().getDataSource()
    }
    single(createdAtStart = true) {
        val database = Database.connect(
            get(),
            {},
            DatabaseConfig {
                useNestedTransactions = true
            }
        )
        TransactionManager.defaultDatabase = database
        database
    }
    single(createdAtStart = true) { JobRunrConfig(get()).run { start() } }
}

val gatewayModule = module {
    val noProxyClient = HttpClientFactory().createClient()
    val proxyClient = HttpClientFactory().createProxyClient()
    single { noProxyClient }
    single(named(HttpClientFactory.Type.NO_PROXY)) { noProxyClient }
    single(named(HttpClientFactory.Type.PROXY)) { proxyClient }
}


val infrastructureModule = module {
    single { TokenUsageRepository() }
    single {
        LLMFactory(
            listOf(
                OpenAIClientProvider(),
                AnthropicClientProvider()
            ),
            get()
        ).promptExecutor()
    }
}

val appModule = module {
    includes(gatewayModule)
    includes(infrastructureModule)
    includes(messageModule)
    includes(emotionModule)
    includes(evolutionModule)
    includes(flowModule)
    includes(memeModule)
    includes(memoryModule)
    includes(summaryModule)
    includes(volitionModule)
    includes(stateDispatchModule)
    includes(cronModule)
    includes(chatModule)
    includes(cleanupModule)
}
