package ai.rever.boss.plugin.dynamic.scheduler

import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.ui.BossPrimaryButton
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy

class SchedPanelComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val services: SchedServices,
) : PanelComponentWithUI, ComponentContext by ctx {

    private val viewModel = SchedPanelViewModel(services)

    init {
        lifecycle.doOnDestroy { viewModel.dispose() }
    }

    @Composable
    override fun Content() {
        BossTheme {
            SchedPanel(viewModel)
        }
    }
}

@Composable
private fun SchedPanel(vm: SchedPanelViewModel) {
    val setup by vm.setup.collectAsState()
    val daemon by vm.daemon.collectAsState()
    val jobs by vm.jobs.collectAsState()
    val jobsError by vm.jobsError.collectAsState()
    val selected by vm.selected.collectAsState()
    val history by vm.history.collectAsState()
    val pendingRemove by vm.pendingRemove.collectAsState()
    val busy by vm.busy.collectAsState()

    // Runs only while the panel is composed (visible), and stops with it.
    LaunchedEffect(Unit) { vm.pollWhileVisible() }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Boss Scheduler",
                color = BossThemeColors.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (busy != null) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                Spacer(Modifier.width(6.dp))
                Text(busy.orEmpty(), color = BossThemeColors.TextMuted, fontSize = 10.sp)
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                Icons.Outlined.Refresh,
                contentDescription = "Refresh",
                tint = BossThemeColors.TextMuted,
                modifier = Modifier.size(14.dp).clickable(onClick = vm::refresh),
            )
        }
        Divider(color = BossThemeColors.BorderColor)

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Section("Status")
            StatusBody(setup, daemon, vm)

            if (setup is SchedSetup.Ready && daemon is SchedDaemon.Running) {
                Section("Jobs (${jobs.size})")
                JobsBody(jobs, jobsError, selected, pendingRemove, vm)

                val sel = jobs.firstOrNull { it.id == selected }
                if (sel != null) {
                    Section("History: ${sel.id}")
                    HistoryBody(history)
                }
            }

            Text(
                "Refreshes every ${SchedPanelViewModel.POLL_MS / 1000}s while this panel is open " +
                    "(boss-sched has no push channel). The scheduler runs under " +
                    "launchd/systemd and keeps running when BOSS is closed.",
                color = BossThemeColors.TextMuted,
                fontSize = 9.sp,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        color = BossThemeColors.TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun StatusBody(setup: SchedSetup, daemon: SchedDaemon, vm: SchedPanelViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        when (setup) {
            SchedSetup.Checking -> Progress("Checking for boss-sched...")
            is SchedSetup.Working -> Progress(setup.step)
            is SchedSetup.Unsupported -> Note("Boss Scheduler runs on macOS and Linux only; it is not supported on ${setup.os}.")
            is SchedSetup.Failed -> {
                Text(setup.reason, color = BossThemeColors.ErrorColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                setup.binary?.let { Field("Binary", it.file.absolutePath) }
                Spacer(Modifier.height(6.dp))
                BossPrimaryButton(text = "Retry", onClick = vm::retrySetup)
            }
            is SchedSetup.Ready -> {
                when (daemon) {
                    is SchedDaemon.Running -> {
                        val s = daemon.status
                        Field("Daemon", "running, pid ${s.pid ?: "-"}", BossThemeColors.SuccessColor)
                        Field("Version", s.version)
                        Field("Since", "${SchedFormat.time(s.startedAt)}")
                        Field("Home", s.home.ifBlank { vm.homePath })
                        Field("Jobs", "${s.jobs} (${s.running} running)")
                    }
                    is SchedDaemon.NotRunning -> {
                        Field("Daemon", "not running", BossThemeColors.ErrorColor)
                        Note(daemon.message)
                        Spacer(Modifier.height(6.dp))
                        BossPrimaryButton(text = "Start", onClick = vm::retrySetup)
                    }
                    SchedDaemon.Unknown -> Progress("Reading status...")
                }
                Field("Binary", setup.binary.file.absolutePath)
                Field("Source", setup.binary.source.label)
            }
        }
    }
}

@Composable
private fun JobsBody(
    jobs: List<SchedJob>,
    error: String?,
    selected: String?,
    pendingRemove: String?,
    vm: SchedPanelViewModel,
) {
    Column(Modifier.fillMaxWidth()) {
        if (error != null) {
            Text(error, color = BossThemeColors.ErrorColor, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp))
        }
        if (jobs.isEmpty()) {
            Note(
                "No jobs. Add one with `boss-sched add ...` (see BossScheduler's README), or ask an agent; " +
                    "it lists them here.",
                Modifier.padding(horizontal = 12.dp),
            )
        }
        jobs.forEach { job ->
            JobRow(job, job.id == selected, pendingRemove == job.id, vm)
            Divider(color = BossThemeColors.BorderColor.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun JobRow(job: SchedJob, isSelected: Boolean, confirmingRemove: Boolean, vm: SchedPanelViewModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (isSelected) BossThemeColors.AccentColor.copy(alpha = 0.12f) else Color.Transparent)
            .clickable { vm.select(job.id) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                job.name.ifBlank { job.id },
                color = if (job.enabled) BossThemeColors.TextPrimary else BossThemeColors.TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StatusText(if (job.isRunning) "running" else job.lastStatus)
        }
        Text(job.id, color = BossThemeColors.TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Text(job.schedule, color = BossThemeColors.TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        val next = if (job.nextRunAt == null) "-" else "${SchedFormat.time(job.nextRunAt)} (${SchedFormat.relative(job.nextRunAt)})"
        Text(
            "next $next  ·  failures ${job.consecutiveFailures}${job.maxConsecutiveFailures?.let { "/$it" } ?: ""}" +
                if (job.enabled) "" else "  ·  disabled",
            color = BossThemeColors.TextMuted,
            fontSize = 9.sp,
        )
        job.disabledReason?.let { Text(it, color = BossThemeColors.WarningColor, fontSize = 9.sp) }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (confirmingRemove) {
                ActionText("Confirm remove", BossThemeColors.ErrorColor) { vm.act(SchedEngine.Action.REMOVE, job.id) }
                ActionText("Cancel") { vm.cancelRemove() }
            } else {
                ActionText("Run now", enabled = !job.isRunning) { vm.act(SchedEngine.Action.RUN_NOW, job.id) }
                if (job.enabled) {
                    ActionText("Disable") { vm.act(SchedEngine.Action.DISABLE, job.id) }
                } else {
                    ActionText("Enable") { vm.act(SchedEngine.Action.ENABLE, job.id) }
                }
                ActionText("Remove", BossThemeColors.ErrorColor) { vm.act(SchedEngine.Action.REMOVE, job.id) }
            }
        }
    }
}

@Composable
private fun HistoryBody(runs: List<SchedRun>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        if (runs.isEmpty()) Note("No runs recorded yet.")
        runs.forEach { r ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusText(r.status)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${SchedFormat.time(r.startedAt)}  ·  ${r.trigger}  ·  ${SchedFormat.exit(r)}",
                        color = BossThemeColors.TextSecondary,
                        fontSize = 9.sp,
                    )
                }
                Text("finished ${SchedFormat.time(r.finishedAt)}", color = BossThemeColors.TextMuted, fontSize = 9.sp)
                r.detail?.let { Text(it, color = BossThemeColors.TextMuted, fontSize = 9.sp) }
                val out = listOfNotNull(
                    r.stdout.takeIf { it.isNotBlank() }?.let { "stdout:\n" + SchedFormat.tail(it, OUTPUT_CHARS) },
                    r.stderr.takeIf { it.isNotBlank() }?.let { "stderr:\n" + SchedFormat.tail(it, OUTPUT_CHARS) },
                ).joinToString("\n")
                if (out.isNotEmpty()) {
                    SelectionContainer {
                        Text(
                            out,
                            color = BossThemeColors.TextSecondary,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth()
                                .background(BossThemeColors.BackgroundColor)
                                .padding(4.dp),
                        )
                    }
                }
            }
            Divider(color = BossThemeColors.BorderColor.copy(alpha = 0.3f))
        }
    }
}

private const val OUTPUT_CHARS = 800

@Composable
private fun StatusText(status: String?) {
    val color = when (status) {
        "success" -> BossThemeColors.SuccessColor
        "running" -> BossThemeColors.AccentColor
        "failed", "timeout", "spawn_error" -> BossThemeColors.ErrorColor
        "skipped", "interrupted" -> BossThemeColors.WarningColor
        else -> BossThemeColors.TextMuted
    }
    Text(status ?: "never run", color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun ActionText(label: String, color: Color = BossThemeColors.AccentColor, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        color = if (enabled) color else BossThemeColors.TextMuted,
        fontSize = 10.sp,
        modifier = if (enabled) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

@Composable
private fun Field(label: String, value: String, valueColor: Color = BossThemeColors.TextPrimary) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, color = BossThemeColors.TextMuted, fontSize = 10.sp, modifier = Modifier.width(56.dp))
        Text(value, color = valueColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Progress(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, color = BossThemeColors.TextMuted, fontSize = 10.sp)
    }
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, color = BossThemeColors.TextMuted, fontSize = 10.sp, modifier = modifier.padding(vertical = 2.dp))
}
