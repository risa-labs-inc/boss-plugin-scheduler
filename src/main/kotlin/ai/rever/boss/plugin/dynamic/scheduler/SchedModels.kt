package ai.rever.boss.plugin.dynamic.scheduler

import java.io.File

/** `boss-sched --json status` while the daemon answers. */
data class SchedStatus(
    val pid: Long?,
    val version: String,
    val home: String,
    val startedAt: String?,
    val jobs: Int,
    val running: Int,
)

/** One entry of `boss-sched --json list`, flattened from `{spec, state}`. */
data class SchedJob(
    val id: String,
    val name: String,
    val enabled: Boolean,
    /** Human form: `30 2 * * * America/Los_Angeles` or `at 2026-10-20T17:00:00Z`. */
    val schedule: String,
    val command: List<String>,
    val nextRunAt: String?,
    val lastStatus: String?,
    val lastFinishedAt: String?,
    val consecutiveFailures: Int,
    val maxConsecutiveFailures: Int?,
    val disabledReason: String?,
    /** Start time of the live run, or null when idle. */
    val runningSince: String?,
) {
    val isRunning: Boolean get() = runningSince != null
}

/** One entry of `boss-sched --json history <id>`. */
data class SchedRun(
    val runId: String,
    val jobId: String,
    val trigger: String,
    val missedSlots: Int,
    val startedAt: String?,
    val finishedAt: String?,
    val status: String,
    val exitCode: Int?,
    val signal: Int?,
    val detail: String?,
    val stdout: String,
    val stderr: String,
)

/** Where the binary came from, which decides whether the plugin may replace it. */
enum class SchedSource(val label: String) {
    /** `~/.boss/scheduler/bin/boss-sched`: the plugin installed it and may update it. */
    PLUGIN("installed by BOSS"),

    /** Anywhere else (PATH, `~/.local/bin`, the LaunchAgent's own path): never touched. */
    EXTERNAL("installed outside BOSS"),
}

data class SchedBinary(val file: File, val source: SchedSource, val version: String?)

/** Install/start progress, owned by [SchedEngine]. */
sealed interface SchedSetup {
    data object Checking : SchedSetup
    data class Unsupported(val os: String) : SchedSetup
    data class Working(val step: String) : SchedSetup
    data class Ready(val binary: SchedBinary) : SchedSetup
    data class Failed(val reason: String, val binary: SchedBinary? = null) : SchedSetup
}

/** Daemon liveness as last observed. */
sealed interface SchedDaemon {
    data object Unknown : SchedDaemon
    data class Running(val status: SchedStatus) : SchedDaemon
    data class NotRunning(val message: String) : SchedDaemon
}
