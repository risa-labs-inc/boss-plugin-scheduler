package ai.rever.boss.plugin.dynamic.scheduler

import java.io.File

/**
 * The OS service that keeps the daemon alive with BOSS closed. `boss-sched
 * install-agent` writes its file; loading it is ours to do.
 */
interface SchedService {
    /** The service file `install-agent` writes. */
    val serviceFile: File

    /** Binary the existing service file runs, if any. */
    fun serviceBinary(): File?

    /** Start the service from its file (first load, or a stopped service). Null on success, else a reason. */
    suspend fun load(): String?

    /** The file changed (new binary path): make the service manager re-read it and start. */
    suspend fun reload(): String?

    /** Restart onto a replaced binary. */
    suspend fun restart(): String?

    /** One-line hint for a person who wants to start it by hand. */
    val manualStartHint: String
}

class LaunchdService(private val paths: SchedPaths) : SchedService {
    override val serviceFile: File get() = paths.launchAgentPlist

    override fun serviceBinary(): File? =
        runCatching { SchedPaths.plistProgram(serviceFile.readText())?.let(::File) }.getOrNull()

    private suspend fun domain(): String? {
        val r = SchedExec.run(listOf("/usr/bin/id", "-u"), timeoutSeconds = 5)
        return r.stdout.trim().toIntOrNull()?.let { "gui/$it" }
    }

    override suspend fun load(): String? {
        val domain = domain() ?: return "could not determine the user id"
        val loaded = launchctl("print", "$domain/${SchedPaths.LAUNCHD_LABEL}").ok
        val r = if (loaded) {
            launchctl("kickstart", "$domain/${SchedPaths.LAUNCHD_LABEL}")
        } else {
            launchctl("bootstrap", domain, serviceFile.absolutePath)
        }
        return if (r.ok) null else "launchctl ${if (loaded) "kickstart" else "bootstrap"} failed: ${r.message}"
    }

    override suspend fun reload(): String? {
        val domain = domain() ?: return "could not determine the user id"
        // Not loaded is the normal case here; ignore its failure.
        launchctl("bootout", "$domain/${SchedPaths.LAUNCHD_LABEL}")
        val r = launchctl("bootstrap", domain, serviceFile.absolutePath)
        return if (r.ok) null else "launchctl bootstrap failed: ${r.message}"
    }

    override suspend fun restart(): String? {
        val domain = domain() ?: return "could not determine the user id"
        val r = launchctl("kickstart", "-k", "$domain/${SchedPaths.LAUNCHD_LABEL}")
        return if (r.ok) null else "launchctl kickstart -k failed: ${r.message}"
    }

    override val manualStartHint =
        "launchctl bootstrap gui/\$(id -u) ~/Library/LaunchAgents/${SchedPaths.LAUNCHD_LABEL}.plist"

    private suspend fun launchctl(vararg args: String) =
        SchedExec.run(listOf("/bin/launchctl") + args, timeoutSeconds = 20)
}

class SystemdService(private val paths: SchedPaths) : SchedService {
    override val serviceFile: File get() = paths.systemdUnit

    override fun serviceBinary(): File? =
        runCatching { SchedPaths.unitProgram(serviceFile.readText())?.let(::File) }.getOrNull()

    override suspend fun load(): String? {
        systemctl("daemon-reload").takeUnless { it.ok }?.let { return "systemctl --user daemon-reload failed: ${it.message}" }
        val r = systemctl("enable", "--now", SchedPaths.SYSTEMD_UNIT)
        return if (r.ok) null else "systemctl --user enable --now failed: ${r.message}"
    }

    override suspend fun reload(): String? {
        load()?.let { return it }
        val r = systemctl("restart", SchedPaths.SYSTEMD_UNIT)
        return if (r.ok) null else "systemctl --user restart failed: ${r.message}"
    }

    override suspend fun restart(): String? {
        val r = systemctl("restart", SchedPaths.SYSTEMD_UNIT)
        return if (r.ok) null else "systemctl --user restart failed: ${r.message}"
    }

    override val manualStartHint = "systemctl --user enable --now ${SchedPaths.SYSTEMD_UNIT}"

    private suspend fun systemctl(vararg args: String): ExecResult {
        val bin = listOf("/usr/bin/systemctl", "/bin/systemctl").firstOrNull { File(it).canExecute() } ?: "systemctl"
        return SchedExec.run(listOf(bin, "--user") + args, timeoutSeconds = 30)
    }
}
