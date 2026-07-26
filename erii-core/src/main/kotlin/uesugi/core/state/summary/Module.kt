package uesugi.core.state.summary

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val summaryModule = module {
    single { SummaryAgent() }
    singleOf(::SummaryRepository)
    singleOf(::SummaryService)
    singleOf(::SummaryJob)
}
