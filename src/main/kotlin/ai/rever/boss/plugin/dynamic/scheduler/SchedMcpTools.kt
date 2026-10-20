package ai.rever.boss.plugin.dynamic.scheduler

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult

/**
 * `scheduler_*` tools, surfaced as `mcp__boss__scheduler_*`.
 *
 * Read-only tools are ungated. `run_now`, `enable` and `disable` need
 * `scheduler.manage` (declared in the manifest): a run executes a job's
 * command on this machine. Adding and removing jobs is deliberately not a
 * tool: agents follow BossScheduler's docs/scheduling-for-agents.md and use
 * the `boss-sched` CLI directly, which keeps one documented path for that.
 */
class SchedMcpToolProvider(
    override val providerId: String,
    private val engine: SchedEngine,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "scheduler_status",
            description = "Boss Scheduler status: whether boss-sched is installed (and where), whether its " +
                "daemon is running, its pid, version, home directory and job counts. Call this first.",
            handler = McpToolHandler {
                engine.refresh()
                McpToolResult(statusReport())
            },
        ),
        McpToolDefinition(
            name = "scheduler_list",
            description = "List Boss Scheduler jobs: id, name, schedule, enabled, next run, last status, " +
                "consecutive failures and disabled reason.",
            handler = McpToolHandler {
                notReady()?.let { return@McpToolHandler it }
                engine.refresh()
                engine.jobsError.value?.let { return@McpToolHandler McpToolResult(it, isError = true) }
                val jobs = engine.jobs.value
                McpToolResult(if (jobs.isEmpty()) "No jobs." else jobs.joinToString("\n") { formatJob(it) })
            },
        ),
        McpToolDefinition(
            name = "scheduler_history",
            description = "Recent runs of one Boss Scheduler job, newest first: status, trigger, start/finish " +
                "times, exit code or signal, detail, and the tail of stdout/stderr.",
            inputSchema = """
                {"type":"object","properties":{
                  "id":{"type":"string","description":"Job id"},
                  "limit":{"type":"integer","description":"Runs to return (default 10, max 50)"}
                },"required":["id"]}
            """.trimIndent(),
            handler = McpToolHandler { args ->
                val cli = engine.cli() ?: return@McpToolHandler notReady()!!
                val id = args.string("id")?.trim().orEmpty()
                if (id.isEmpty()) return@McpToolHandler McpToolResult("id is required", isError = true)
                val limit = (args.int("limit") ?: 10).coerceIn(1, 50)
                when (val r = cli.history(id, limit)) {
                    is SchedCli.Result.Err -> McpToolResult(r.message, isError = true)
                    is SchedCli.Result.Ok -> McpToolResult(
                        if (r.value.isEmpty()) "No runs recorded for $id." else r.value.asReversed().joinToString("\n\n") { formatRun(it) },
                    )
                }
            },
        ),
        action("scheduler_run_now", "Start a run of a Boss Scheduler job immediately (does not move its schedule; " +
            "works on disabled jobs). Returns at once; check scheduler_history for the result.", SchedEngine.Action.RUN_NOW),
        action("scheduler_enable", "Enable a Boss Scheduler job. Resets its failure counter and schedules it from " +
            "the next future slot.", SchedEngine.Action.ENABLE),
        action("scheduler_disable", "Disable a Boss Scheduler job so it stops firing. Its history is kept.", SchedEngine.Action.DISABLE),
    )

    private fun action(name: String, description: String, action: SchedEngine.Action) = McpToolDefinition.withRbac(
        name = name,
        description = description,
        inputSchema = """{"type":"object","properties":{"id":{"type":"string","description":"Job id"}},"required":["id"]}""",
        readOnly = false,
        requiredPermissions = listOf(PERMISSION_MANAGE),
        handler = McpToolHandler { args ->
            notReady()?.let { return@McpToolHandler it }
            val id = args.string("id")?.trim().orEmpty()
            if (id.isEmpty()) return@McpToolHandler McpToolResult("id is required", isError = true)
            val (message, isError) = engine.act(action, id)
            McpToolResult(message, isError = isError)
        },
    )

    private fun notReady(): McpToolResult? = if (engine.cli() == null) McpToolResult(statusReport(), isError = true) else null

    internal fun statusReport(): String = buildString {
        when (val s = engine.setup.value) {
            SchedSetup.Checking -> appendLine("Install: checking")
            is SchedSetup.Working -> appendLine("Install: in progress (${s.step})")
            is SchedSetup.Unsupported -> appendLine("Install: Boss Scheduler is not supported on ${s.os}")
            is SchedSetup.Failed -> appendLine("Install: failed - ${s.reason}")
            is SchedSetup.Ready -> appendLine(
                "Binary: ${s.binary.file.absolutePath} (${s.binary.source.label}, version ${s.binary.version ?: "unknown"})",
            )
        }
        when (val d = engine.daemon.value) {
            SchedDaemon.Unknown -> appendLine("Daemon: unknown")
            is SchedDaemon.NotRunning -> appendLine("Daemon: not running (${d.message})")
            is SchedDaemon.Running -> with(d.status) {
                appendLine("Daemon: running, pid $pid, version $version, since ${startedAt ?: "-"}")
                appendLine("Home: $home")
                appendLine("Jobs: $jobs, running now: $running")
            }
        }
    }.trimEnd()

    companion object {
        const val PERMISSION_MANAGE = "scheduler.manage"

        internal fun formatJob(j: SchedJob): String = buildString {
            append("${j.id}  \"${j.name}\"  schedule=${j.schedule}  enabled=${j.enabled}")
            append("  next=${j.nextRunAt ?: "-"}  last=${if (j.isRunning) "running" else j.lastStatus ?: "-"}")
            append("  failures=${j.consecutiveFailures}")
            j.disabledReason?.let { append("  disabled_reason=\"$it\"") }
        }

        internal fun formatRun(r: SchedRun): String = buildString {
            append("${r.runId}  ${r.status}  trigger=${r.trigger}  started=${r.startedAt ?: "-"}  finished=${r.finishedAt ?: "-"}")
            append("  ${SchedFormat.exit(r)}")
            r.detail?.let { append("  detail=\"$it\"") }
            if (r.stdout.isNotBlank()) append("\nstdout:\n").append(SchedFormat.tail(r.stdout, 2000))
            if (r.stderr.isNotBlank()) append("\nstderr:\n").append(SchedFormat.tail(r.stderr, 2000))
        }
    }
}
