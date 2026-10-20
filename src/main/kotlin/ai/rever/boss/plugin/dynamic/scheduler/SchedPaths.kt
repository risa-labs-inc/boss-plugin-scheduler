package ai.rever.boss.plugin.dynamic.scheduler

import java.io.File

/**
 * Where things live. Pure: nothing here creates a file.
 *
 * [userHome] is the OS user's home; the scheduler's own home is
 * `~/.boss/scheduler` unless `BOSS_SCHED_HOME` says otherwise (the same rule
 * the CLI applies).
 */
class SchedPaths(
    val userHome: File = File(System.getProperty("user.home")),
    private val env: Map<String, String> = System.getenv(),
) {
    val schedHome: File = env["BOSS_SCHED_HOME"]?.takeIf { it.isNotBlank() }?.let(::File)
        ?: File(userHome, ".boss/scheduler")

    /** The plugin-managed binary. Matches docs/scheduling-for-agents.md's fallback. */
    val managedBinary: File = File(schedHome, "bin/${SchedRelease.BINARY_NAME}")

    val launchAgentPlist: File = File(userHome, "Library/LaunchAgents/$LAUNCHD_LABEL.plist")
    val systemdUnit: File = File(userHome, ".config/systemd/user/$SYSTEMD_UNIT")

    /**
     * Places an existing install is looked for, most authoritative first:
     * the binary the service file already runs, then PATH, then the two known
     * install locations. De-duplicated by canonical path.
     */
    fun candidates(serviceBinary: File?, pathEnv: String = env["PATH"].orEmpty()): List<File> {
        val fromPath = pathEnv.split(File.pathSeparatorChar).filter { it.isNotBlank() }
            .map { File(it, SchedRelease.BINARY_NAME) }
        val all = listOfNotNull(serviceBinary) + fromPath +
            File(userHome, ".local/bin/${SchedRelease.BINARY_NAME}") + managedBinary
        val seen = LinkedHashMap<String, File>()
        for (f in all) {
            if (!f.isFile || !f.canExecute()) continue
            val key = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
            seen.putIfAbsent(key, f)
        }
        return seen.values.toList()
    }

    fun sourceOf(binary: File): SchedSource {
        val a = runCatching { binary.canonicalPath }.getOrDefault(binary.absolutePath)
        val b = runCatching { managedBinary.canonicalPath }.getOrDefault(managedBinary.absolutePath)
        return if (a == b) SchedSource.PLUGIN else SchedSource.EXTERNAL
    }

    companion object {
        const val LAUNCHD_LABEL = "ai.rever.boss.scheduler"
        const val SYSTEMD_UNIT = "boss-scheduler.service"

        /** First `ProgramArguments` string of a plist written by `install-agent`. */
        fun plistProgram(plist: String): String? {
            val args = Regex("""<key>ProgramArguments</key>\s*<array>(.*?)</array>""", RegexOption.DOT_MATCHES_ALL)
                .find(plist)?.groupValues?.get(1) ?: return null
            val first = Regex("""<string>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .find(args)?.groupValues?.get(1) ?: return null
            return xmlUnescape(first.trim()).takeIf { it.isNotEmpty() }
        }

        /** Binary of an `ExecStart=` line written by `install-agent` (quoted, `%%`/`$$` doubled). */
        fun unitProgram(unit: String): String? {
            val line = unit.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("ExecStart=") }
                ?: return null
            val rest = line.removePrefix("ExecStart=").trim()
            val raw = if (rest.startsWith("\"")) {
                val sb = StringBuilder()
                var i = 1
                while (i < rest.length) {
                    val c = rest[i]
                    if (c == '\\' && i + 1 < rest.length) {
                        sb.append(rest[i + 1]); i += 2; continue
                    }
                    if (c == '"') break
                    sb.append(c); i++
                }
                sb.toString()
            } else {
                rest.substringBefore(' ')
            }
            return raw.replace("%%", "%").replace("$$", "$").takeIf { it.isNotEmpty() }
        }

        private fun xmlUnescape(s: String) =
            s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

        /**
         * [first] then [rest] (a PATH string), de-duplicated, order kept. The
         * service file records this PATH for every job, so jobs can find
         * Homebrew tools and agent CLIs even though BOSS itself may have been
         * launched from Finder with a minimal PATH.
         */
        fun mergePath(first: List<String>, rest: String): String {
            val seen = LinkedHashSet<String>()
            (first + rest.split(File.pathSeparatorChar)).map { it.trim() }.filter { it.isNotEmpty() }
                .forEach { seen.add(it) }
            return seen.joinToString(File.pathSeparator)
        }

        /** Extract the PATH a login shell printed between [marker]s, ignoring rc-file noise. */
        fun parseMarkedPath(output: String, marker: String = PATH_MARKER): String? {
            val start = output.indexOf(marker)
            if (start < 0) return null
            val end = output.indexOf(marker, start + marker.length)
            if (end < 0) return null
            return output.substring(start + marker.length, end).trim().takeIf { it.isNotEmpty() }
        }

        const val PATH_MARKER = "__BOSS_SCHED_PATH__"
    }
}
