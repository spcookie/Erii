package uesugi.core.state.dispatch

import org.jobrunr.scheduling.JobScheduler
import uesugi.common.toolkit.ConfigHolder
import uesugi.common.toolkit.logger

class StateDispatchJob(
    private val jobScheduler: JobScheduler,
    private val coordinator: StateWorkCoordinator
) {
    private val log = logger()

    fun open() {
        val profile = ConfigHolder.getStateTuning().dispatch.profile
        val minutes = profile.reconciliationInterval.inWholeMinutes.coerceAtLeast(1)
        val cron = if (minutes == 1L) "* * * * *" else "*/$minutes * * * *"

        coordinator.start()
        coordinator.reconcile()
        jobScheduler.scheduleRecurrently(
            "state-reconciliation-job",
            cron,
            ::reconcile
        )
        log.info("State dispatch started, profile=$profile, reconciliation=${minutes}m")
    }

    fun reconcile() {
        coordinator.reconcile()
    }
}
