package ai.rever.boss.plugin.dynamic.scheduler

/**
 * The BossScheduler release contract, from that repo's `.github/workflows/release.yml`:
 *
 * ```
 * https://github.com/risa-labs-inc/BossScheduler/releases/download/v<version>/boss-sched-<version>-<target>.tar.gz
 * https://github.com/risa-labs-inc/BossScheduler/releases/download/v<version>/SHA256SUMS
 * ```
 *
 * Each archive holds exactly one `boss-sched` at its root. Changing names or
 * layout there is a breaking change for this plugin.
 */
object SchedRelease {

    /**
     * The scheduler release this plugin installs. Pinned, never "latest": the
     * JSON shapes in [SchedJson] were read against this version's CLI.
     */
    const val PINNED_VERSION = "0.1.0"

    const val DEFAULT_BASE_URL = "https://github.com/risa-labs-inc/BossScheduler/releases/download"

    const val BINARY_NAME = "boss-sched"

    fun archiveName(version: String, target: String) = "boss-sched-$version-$target.tar.gz"

    fun archiveUrl(baseUrl: String, version: String, target: String) =
        "${baseUrl.trimEnd('/')}/v$version/${archiveName(version, target)}"

    fun checksumsUrl(baseUrl: String, version: String) = "${baseUrl.trimEnd('/')}/v$version/SHA256SUMS"
}

/** Which OS family the host is on, as far as the scheduler cares. */
enum class SchedOs { MAC, LINUX, UNSUPPORTED }

object SchedPlatform {

    fun os(osName: String = System.getProperty("os.name").orEmpty()): SchedOs {
        val n = osName.lowercase()
        return when {
            n.startsWith("mac") || n.contains("darwin") -> SchedOs.MAC
            n.contains("linux") -> SchedOs.LINUX
            else -> SchedOs.UNSUPPORTED
        }
    }

    /**
     * The release target triple for this OS/arch, or null when there is no
     * build for it (Windows, 32-bit, other CPUs).
     *
     * `os.arch` is what the JVM reports, not `uname -m`: `aarch64` on Apple
     * silicon and arm64 Linux, `amd64` on x86_64 Linux, `x86_64` on Intel
     * macOS. A Rosetta-translated x86_64 JVM on Apple silicon reports
     * `x86_64` and gets the Intel binary, which also runs under Rosetta.
     */
    fun targetTriple(
        osName: String = System.getProperty("os.name").orEmpty(),
        osArch: String = System.getProperty("os.arch").orEmpty(),
    ): String? {
        val arch = when (osArch.lowercase()) {
            "aarch64", "arm64" -> "aarch64"
            "x86_64", "amd64", "x64" -> "x86_64"
            else -> return null
        }
        return when (os(osName)) {
            SchedOs.MAC -> "$arch-apple-darwin"
            SchedOs.LINUX -> "$arch-unknown-linux-gnu"
            SchedOs.UNSUPPORTED -> null
        }
    }
}

/** Dotted numeric version comparison; non-numeric suffixes are ignored. */
object SchedVersion {
    fun compare(a: String, b: String): Int {
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private fun parts(v: String): List<Int> =
        v.trim().removePrefix("v").substringBefore('-').substringBefore('+')
            .split('.')
            .map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
}
