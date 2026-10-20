package ai.rever.boss.plugin.dynamic.scheduler

import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration

/**
 * Download, verify and place the pinned `boss-sched` release.
 *
 * Order matters and every step can fail without touching the installed
 * binary:
 *
 * 1. fetch `SHA256SUMS` and find this target's archive in it,
 * 2. fetch the archive into a temp dir next to the destination,
 * 3. check its SHA-256,
 * 4. extract the single `boss-sched` entry to a temp file in the destination
 *    directory, chmod 755, and run `--json version` on it,
 * 5. atomically rename it over the destination.
 *
 * Step 5 is a rename, never a copy over the existing file: on macOS writing
 * into a running, signed Mach-O in place invalidates its code signature and
 * the kernel kills the process (and the next launch) with SIGKILL. A rename
 * leaves the old inode intact for whatever is still running it.
 */
class SchedDownloader(
    private val baseUrl: String = SchedRelease.DEFAULT_BASE_URL,
    private val version: String = SchedRelease.PINNED_VERSION,
    private val http: HttpClient = defaultClient(),
) {

    class InstallException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Install the release for [target] at [destination]. Returns the version
     * the new binary reports. Throws [InstallException] with a readable reason.
     * [progress] receives short step descriptions for the UI.
     */
    fun install(target: String, destination: File, progress: (String) -> Unit = {}): String {
        val dir = destination.absoluteFile.parentFile
        if (!dir.isDirectory && !dir.mkdirs()) throw InstallException("cannot create ${dir.path}")
        val archiveName = SchedRelease.archiveName(version, target)

        progress("Fetching checksums for v$version")
        val sums = SchedChecksums.parse(String(get(SchedRelease.checksumsUrl(baseUrl, version), MAX_SUMS_BYTES), Charsets.UTF_8))
        val expected = sums[archiveName]
            ?: throw InstallException("SHA256SUMS for v$version has no entry for $archiveName")

        val work = Files.createTempDirectory(dir.toPath(), ".download-").toFile()
        try {
            progress("Downloading $archiveName")
            val archive = File(work, archiveName)
            archive.writeBytes(get(SchedRelease.archiveUrl(baseUrl, version, target), MAX_ARCHIVE_BYTES))

            progress("Verifying checksum")
            val actual = SchedChecksums.sha256(archive)
            if (!actual.equals(expected, ignoreCase = true)) {
                throw InstallException("checksum mismatch for $archiveName: expected $expected, got $actual")
            }

            progress("Extracting")
            val staged = File(dir, ".${SchedRelease.BINARY_NAME}.new-${System.nanoTime()}")
            try {
                archive.inputStream().use { input ->
                    staged.outputStream().use { out -> SchedTar.extractGz(input, SchedRelease.BINARY_NAME, out) }
                }
            } catch (e: SchedTar.ArchiveException) {
                staged.delete()
                throw InstallException("bad archive $archiveName: ${e.message}", e)
            }
            try {
                makeExecutable(staged)
                clearQuarantine(staged)

                val reported = SchedJson.parseVersion(
                    SchedExec.runBlocking(listOf(staged.absolutePath, "--json", "version"), timeoutSeconds = 15).stdout,
                ) ?: throw InstallException("the downloaded boss-sched did not run (`--json version` gave nothing)")
                if (SchedVersion.compare(reported, version) != 0) {
                    throw InstallException("the downloaded boss-sched reports $reported, expected $version")
                }

                try {
                    Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (e: AtomicMoveNotSupportedException) {
                    // Same directory, so this should not happen; never fall back to a copy.
                    throw InstallException("cannot atomically replace ${destination.path}: ${e.message}", e)
                }
                return reported
            } finally {
                staged.delete()
            }
        } finally {
            work.deleteRecursively()
        }
    }

    private fun get(url: String, maxBytes: Long): ByteArray {
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(120))
            .header("User-Agent", "boss-plugin-scheduler")
            .GET()
            .build()
        val resp = try {
            http.send(req, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: IOException) {
            throw InstallException("download failed: $url (${e.message ?: e::class.simpleName})", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InstallException("download interrupted: $url", e)
        }
        resp.body().use { body ->
            if (resp.statusCode() != 200) {
                val hint = if (resp.statusCode() == 404) " (is release v$version published?)" else ""
                throw InstallException("HTTP ${resp.statusCode()} for $url$hint")
            }
            val bytes = body.readNBytes((maxBytes + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            if (bytes.size > maxBytes) throw InstallException("$url is larger than $maxBytes bytes")
            return bytes
        }
    }

    companion object {
        private const val MAX_SUMS_BYTES = 64L * 1024
        private const val MAX_ARCHIVE_BYTES = 128L * 1024 * 1024

        fun defaultClient(): HttpClient = HttpClient.newBuilder()
            // GitHub release downloads redirect to objects.githubusercontent.com.
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build()

        fun makeExecutable(f: File) {
            runCatching { Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rwxr-xr-x")) }
                .onFailure { f.setExecutable(true, false) }
        }

        /**
         * Release macOS binaries are unsigned. A JVM download is not normally
         * quarantined, but a host with LSFileQuarantineEnabled would tag it, so
         * clear the attribute; failure (no attribute, no xattr) is fine.
         */
        fun clearQuarantine(f: File) {
            if (SchedPlatform.os() != SchedOs.MAC) return
            val xattr = File("/usr/bin/xattr")
            if (!xattr.canExecute()) return
            SchedExec.runBlocking(listOf(xattr.path, "-d", "com.apple.quarantine", f.absolutePath), timeoutSeconds = 5)
        }
    }
}
