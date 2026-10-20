package ai.rever.boss.plugin.dynamic.scheduler

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * All scheduler state and every operation. The plugin is an installer and a
 * front end: the daemon is a separate process owned by launchd / systemd so it
 * keeps running when BOSS is closed. Nothing here ever runs `daemon` itself.
 *
 * Everything is injectable so the install flow can be tested against a fake
 * binary, a fake service manager and a local release server.
 */
class SchedEngine(
    private val paths: SchedPaths = SchedPaths(),
    private val os: SchedOs = SchedPlatform.os(),
    private val target: String? = SchedPlatform.targetTriple(),
    private val downloader: SchedDownloader = SchedDownloader(),
    private val serviceFactory: (SchedOs, SchedPaths) -> SchedService? = ::defaultService,
    private val jobPath: suspend () -> String = { sensibleJobPath() },
    private val startWaitMs: Long = 10_000,
) {
    private val _setup = MutableStateFlow<SchedSetup>(SchedSetup.Checking)
    val setup: StateFlow<SchedSetup> = _setup.asStateFlow()

    private val _daemon = MutableStateFlow<SchedDaemon>(SchedDaemon.Unknown)
    val daemon: StateFlow<SchedDaemon> = _daemon.asStateFlow()

    private val _jobs = MutableStateFlow<List<SchedJob>>(emptyList())
    val jobs: StateFlow<List<SchedJob>> = _jobs.asStateFlow()

    private val _jobsError = MutableStateFlow<String?>(null)
    val jobsError: StateFlow<String?> = _jobsError.asStateFlow()

    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected.asStateFlow()

    private val _history = MutableStateFlow<List<SchedRun>>(emptyList())
    val history: StateFlow<List<SchedRun>> = _history.asStateFlow()

    private val ensureLock = Mutex()
    private val refreshLock = Mutex()

    val schedHome: File get() = paths.schedHome

    val service: SchedService? by lazy { serviceFactory(os, paths) }

    /** The CLI for the ready binary, or null until [ensure] has found or installed one. */
    fun cli(): SchedCli? = (setup.value as? SchedSetup.Ready)?.binary?.file?.let(::SchedCli)

    /**
     * Make sure the scheduler is installed and running. Safe to call again
     * (Retry); concurrent calls collapse into one. Never throws.
     */
    suspend fun ensure() {
        if (!ensureLock.tryLock()) return
        try {
            withContext(Dispatchers.IO) { _setup.value = runCatching { doEnsure() }.getOrElse { SchedSetup.Failed(it.message ?: it.toString()) } }
        } finally {
            ensureLock.unlock()
        }
        refresh()
    }

    private suspend fun doEnsure(): SchedSetup {
        if (os == SchedOs.UNSUPPORTED) {
            return SchedSetup.Unsupported(System.getProperty("os.name").orEmpty())
        }
        val service = service ?: return SchedSetup.Unsupported(System.getProperty("os.name").orEmpty())
        _setup.value = SchedSetup.Checking

        val serviceBinary = service.serviceBinary()?.takeIf { it.isFile && it.canExecute() }
        var binary = paths.candidates(serviceBinary).firstOrNull()
        var replaced = false

        if (binary == null) {
            val t = target ?: return SchedSetup.Failed("no Boss Scheduler build for ${System.getProperty("os.arch")}")
            install(t)?.let { return it }
            binary = paths.managedBinary
        } else if (paths.sourceOf(binary) == SchedSource.PLUGIN) {
            val have = SchedCli(binary).version()
            if (have == null || SchedVersion.compare(have, SchedRelease.PINNED_VERSION) < 0) {
                val t = target
                if (t != null) {
                    install(t)?.let { return it }
                    replaced = true
                }
            }
        }

        val cli = SchedCli(binary)
        val found = SchedBinary(binary, paths.sourceOf(binary), cli.version())

        when (cli.status()) {
            is SchedDaemon.Running -> {
                if (replaced) {
                    _setup.value = SchedSetup.Working("Restarting onto v${SchedRelease.PINNED_VERSION}")
                    service.restart()?.let { return SchedSetup.Failed(it, found) }
                    awaitRunning(cli)?.let { return SchedSetup.Failed(it, found) }
                }
                return SchedSetup.Ready(found)
            }
            else -> Unit
        }

        // Installed but not running. Write the service file when there is none
        // or it runs a different binary, then load it.
        val sameBinary = serviceBinary != null && canonical(serviceBinary) == canonical(binary)
        if (!service.serviceFile.isFile || !sameBinary) {
            _setup.value = SchedSetup.Working("Writing ${service.serviceFile.name}")
            val r = cli.installAgent(jobPath())
            if (!r.ok) return SchedSetup.Failed("install-agent failed: ${r.message}", found)
            _setup.value = SchedSetup.Working("Starting the scheduler")
            service.reload()?.let { return SchedSetup.Failed(it, found) }
        } else {
            _setup.value = SchedSetup.Working("Starting the scheduler")
            service.load()?.let { return SchedSetup.Failed(it, found) }
        }
        awaitRunning(cli)?.let { return SchedSetup.Failed("$it. Start it by hand with: ${service.manualStartHint}", found) }
        return SchedSetup.Ready(found)
    }

    /** Null on success, else the install failure as a setup state. */
    private fun install(target: String): SchedSetup? {
        return try {
            downloader.install(target, paths.managedBinary) { step -> _setup.value = SchedSetup.Working(step) }
            null
        } catch (e: SchedDownloader.InstallException) {
            SchedSetup.Failed("Could not install Boss Scheduler v${SchedRelease.PINNED_VERSION}: ${e.message}")
        }
    }

    private suspend fun awaitRunning(cli: SchedCli): String? {
        val deadline = System.currentTimeMillis() + startWaitMs
        var last: SchedDaemon = SchedDaemon.Unknown
        while (System.currentTimeMillis() < deadline) {
            last = cli.status()
            if (last is SchedDaemon.Running) {
                _daemon.value = last
                return null
            }
            delay(250)
        }
        return "the scheduler did not come up within ${startWaitMs / 1000}s (${(last as? SchedDaemon.NotRunning)?.message ?: "no answer"})"
    }

    /** Status, jobs and the selected job's history. Overlapping calls are dropped. */
    suspend fun refresh() {
        val cli = cli() ?: return
        if (!refreshLock.tryLock()) return
        try {
            val status = cli.status()
            _daemon.value = status
            if (status !is SchedDaemon.Running) {
                _jobsError.value = null
                return
            }
            when (val r = cli.list()) {
                is SchedCli.Result.Ok -> {
                    _jobs.value = r.value
                    _jobsError.value = null
                }
                is SchedCli.Result.Err -> _jobsError.value = r.message
            }
            val sel = _selected.value
            if (sel != null && _jobs.value.none { it.id == sel }) {
                _selected.value = null
                _history.value = emptyList()
            } else if (sel != null) {
                (cli.history(sel, HISTORY_LIMIT) as? SchedCli.Result.Ok)?.let { _history.value = it.value.asReversed() }
            }
        } finally {
            refreshLock.unlock()
        }
    }

    suspend fun select(id: String?) {
        _selected.value = id
        _history.value = emptyList()
        if (id != null) {
            val r = cli()?.history(id, HISTORY_LIMIT)
            if (r is SchedCli.Result.Ok && _selected.value == id) _history.value = r.value.asReversed()
        }
    }

    enum class Action(val verb: String) { RUN_NOW("Started"), ENABLE("Enabled"), DISABLE("Disabled"), REMOVE("Removed") }

    /** Perform [action] on job [id]. Returns (message, isError). */
    suspend fun act(action: Action, id: String): Pair<String, Boolean> {
        val cli = cli() ?: return "Boss Scheduler is not ready" to true
        val r = when (action) {
            Action.RUN_NOW -> cli.runNow(id)
            Action.ENABLE -> cli.enable(id)
            Action.DISABLE -> cli.disable(id)
            Action.REMOVE -> cli.remove(id)
        }
        refresh()
        return when (r) {
            is SchedCli.Result.Ok -> "${action.verb} $id" to false
            is SchedCli.Result.Err -> "$id: ${r.message}" to true
        }
    }

    private fun canonical(f: File) = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)

    companion object {
        const val HISTORY_LIMIT = 20

        fun defaultService(os: SchedOs, paths: SchedPaths): SchedService? = when (os) {
            SchedOs.MAC -> LaunchdService(paths)
            SchedOs.LINUX -> SystemdService(paths)
            SchedOs.UNSUPPORTED -> null
        }

        /**
         * PATH recorded in the service file for jobs: the login shell's PATH
         * (where agent CLIs and Homebrew live), our usual directories, then
         * whatever BOSS inherited. A Finder-launched BOSS has a minimal PATH,
         * so passing ours through unchanged would leave jobs unable to find
         * `claude`, `node` and friends.
         */
        suspend fun sensibleJobPath(): String {
            val shell = System.getenv("SHELL")?.takeIf { File(it).canExecute() }
                ?: listOf("/bin/zsh", "/bin/bash", "/bin/sh").first { File(it).canExecute() }
            val m = SchedPaths.PATH_MARKER
            val r = SchedExec.run(listOf(shell, "-l", "-c", "printf '%s%s%s' '$m' \"\$PATH\" '$m'"), timeoutSeconds = 5)
            val login = SchedPaths.parseMarkedPath(r.stdout).orEmpty()
            val dirs = login.split(File.pathSeparatorChar) + SchedExec.extraDirs()
            return SchedPaths.mergePath(dirs, System.getenv("PATH").orEmpty())
        }
    }
}
