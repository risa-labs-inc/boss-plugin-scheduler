package ai.rever.boss.plugin.dynamic.scheduler

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Outcome of one child process. Negative exit codes are synthetic. */
data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
    val message: String get() = stderr.ifBlank { stdout }.trim()

    companion object {
        const val EXIT_START_FAILED = -1
        const val EXIT_TIMEOUT = -2
    }
}

/**
 * The single place this plugin starts a process.
 *
 * - argv lists only, never a shell string.
 * - Absolute binaries, and a widened child PATH: the packaged host launched
 *   from Finder has a nearly empty PATH, so a bare name resolves to nothing.
 * - stdout and stderr drained concurrently, so a chatty child cannot deadlock
 *   on a full pipe.
 * - Blocking wait inside [Dispatchers.IO] so MCP timeouts can cancel the caller.
 */
object SchedExec {

    /** Directories prepended to a child's PATH (and searched by [which]). */
    fun extraDirs(home: String = System.getProperty("user.home").orEmpty()): List<String> = listOfNotNull(
        home.takeIf { it.isNotBlank() }?.let { "$it/.boss/scheduler/bin" },
        home.takeIf { it.isNotBlank() }?.let { "$it/.local/bin" },
        "/opt/homebrew/bin",
        "/usr/local/bin",
        "/usr/bin",
        "/bin",
        "/usr/sbin",
        "/sbin",
    )

    fun childPath(): String = SchedPaths.mergePath(extraDirs(), System.getenv("PATH").orEmpty())

    suspend fun run(
        argv: List<String>,
        timeoutSeconds: Long = 20,
        extraEnv: Map<String, String> = emptyMap(),
    ): ExecResult = withContext(Dispatchers.IO) { runBlocking(argv, timeoutSeconds, extraEnv) }

    fun runBlocking(
        argv: List<String>,
        timeoutSeconds: Long = 20,
        extraEnv: Map<String, String> = emptyMap(),
    ): ExecResult {
        val builder = ProcessBuilder(argv).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        builder.environment()["PATH"] = childPath()
        builder.environment().putAll(extraEnv)
        val process = try {
            builder.start()
        } catch (e: Exception) {
            return ExecResult(ExecResult.EXIT_START_FAILED, "", e.message ?: e.toString())
        }
        val out = StringBuffer()
        val err = StringBuffer()
        val outReader = Thread { runCatching { process.inputStream.bufferedReader().forEachLine { out.appendLine(it) } } }
        val errReader = Thread { runCatching { process.errorStream.bufferedReader().forEachLine { err.appendLine(it) } } }
        outReader.isDaemon = true
        errReader.isDaemon = true
        outReader.start()
        errReader.start()
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return ExecResult(ExecResult.EXIT_TIMEOUT, out.toString(), err.toString() + "timed out after ${timeoutSeconds}s")
            }
            outReader.join(DRAIN_MS)
            errReader.join(DRAIN_MS)
            return ExecResult(process.exitValue(), out.toString(), err.toString())
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            throw e
        }
    }

    private const val DRAIN_MS = 2_000L
}

/**
 * Thin client over the `boss-sched` CLI. Every call uses `--json`; the human
 * output is not a contract.
 */
class SchedCli(private val binary: File) {

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Err(val message: String) : Result<Nothing>
    }

    suspend fun version(): String? {
        val r = SchedExec.run(listOf(binary.absolutePath, "--json", "version"), timeoutSeconds = 10)
        return if (r.ok) SchedJson.parseVersion(r.stdout) else null
    }

    /** Running status, or [SchedDaemon.NotRunning] (exit 3) with the CLI's reason. */
    suspend fun status(): SchedDaemon {
        val r = SchedExec.run(listOf(binary.absolutePath, "--json", "status"), timeoutSeconds = 10)
        if (!r.ok) {
            return SchedDaemon.NotRunning(r.message.ifBlank { "exit ${r.exitCode}" })
        }
        return runCatching { SchedDaemon.Running(SchedJson.parseStatus(r.stdout)) }
            .getOrElse { SchedDaemon.NotRunning("unreadable status: ${it.message}") }
    }

    suspend fun list(): Result<List<SchedJob>> = call(listOf("list")) { SchedJson.parseJobs(it) }

    suspend fun history(id: String, limit: Int = 20): Result<List<SchedRun>> =
        call(listOf("history", id, "--limit", limit.toString())) { SchedJson.parseHistory(it) }

    suspend fun runNow(id: String): Result<String> = call(listOf("run-now", id)) { it.trim() }
    suspend fun enable(id: String): Result<String> = call(listOf("enable", id)) { it.trim() }
    suspend fun disable(id: String): Result<String> = call(listOf("disable", id)) { it.trim() }
    suspend fun remove(id: String): Result<String> = call(listOf("remove", id)) { it.trim() }

    /** `install-agent` with [path] as the PATH the service file records for jobs. */
    suspend fun installAgent(path: String): ExecResult =
        SchedExec.run(listOf(binary.absolutePath, "install-agent"), extraEnv = mapOf("PATH" to path))

    private suspend fun <T> call(args: List<String>, parse: (String) -> T): Result<T> {
        val r = SchedExec.run(listOf(binary.absolutePath, "--json") + args)
        if (!r.ok) return Result.Err(r.message.removePrefix("boss-sched: ").ifBlank { "exit ${r.exitCode}" })
        return runCatching { Result.Ok(parse(r.stdout)) }
            .getOrElse { Result.Err("unexpected output from boss-sched: ${it.message}") }
    }
}
