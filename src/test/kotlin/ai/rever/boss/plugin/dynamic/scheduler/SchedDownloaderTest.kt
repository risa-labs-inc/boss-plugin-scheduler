package ai.rever.boss.plugin.dynamic.scheduler

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The download path end to end against a local HTTP fixture, since the real
 * v0.1.0 release does not exist yet.
 */
class SchedDownloaderTest {

    private val dir: File = Files.createTempDirectory("sched-dl").toFile()
    private val state = File(dir, "state").apply { mkdirs() }
    private val dest = File(dir, "home/.boss/scheduler/bin/boss-sched")
    private val target = "aarch64-apple-darwin"

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `downloads, verifies, extracts and places an executable binary`() {
        ReleaseFixture("0.1.0", target, ReleaseFixture.fakeBossSched("0.1.0", state)).use { rel ->
            val steps = mutableListOf<String>()
            val v = SchedDownloader(rel.baseUrl, "0.1.0").install(target, dest) { steps += it }
            assertEquals("0.1.0", v)
            assertTrue(dest.isFile && dest.canExecute())
            assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(dest.toPath())))
            assertTrue(steps.any { it.startsWith("Verifying") })
            // No temp files left next to the binary.
            assertEquals(listOf("boss-sched"), dest.parentFile.list()!!.toList())
        }
    }

    @Test
    fun `replacing an existing binary is a rename, not an in-place write`() {
        dest.parentFile.mkdirs()
        dest.writeText("#!/bin/sh\necho old\n")
        val oldInode = Files.getAttribute(dest.toPath(), "unix:ino")
        ReleaseFixture("0.1.0", target, ReleaseFixture.fakeBossSched("0.1.0", state)).use { rel ->
            SchedDownloader(rel.baseUrl, "0.1.0").install(target, dest)
        }
        assertTrue(dest.readText().contains("version"))
        assertTrue(oldInode != Files.getAttribute(dest.toPath(), "unix:ino"), "expected a new inode (atomic rename)")
    }

    @Test
    fun `checksum mismatch fails and leaves the existing binary alone`() {
        dest.parentFile.mkdirs()
        dest.writeText("original")
        ReleaseFixture("0.1.0", target, ReleaseFixture.fakeBossSched("0.1.0", state), tamper = { it.copyOf().also { b -> b[b.size - 5] = (b[b.size - 5] + 1).toByte() } }).use { rel ->
            val e = assertFailsWith<SchedDownloader.InstallException> { SchedDownloader(rel.baseUrl, "0.1.0").install(target, dest) }
            assertTrue(e.message!!.contains("checksum mismatch"), e.message)
        }
        assertEquals("original", dest.readText())
        assertEquals(listOf("boss-sched"), dest.parentFile.list()!!.toList())
    }

    @Test
    fun `target missing from SHA256SUMS fails before downloading the archive`() {
        ReleaseFixture("0.1.0", target, ReleaseFixture.fakeBossSched("0.1.0", state), sumsOverride = "${"1".repeat(64)}  something-else.tar.gz\n").use { rel ->
            val e = assertFailsWith<SchedDownloader.InstallException> { SchedDownloader(rel.baseUrl, "0.1.0").install(target, dest) }
            assertTrue(e.message!!.contains("no entry"), e.message)
            assertEquals(1, rel.requests.get())
        }
    }

    @Test
    fun `unpublished release gives a readable 404`() {
        ReleaseFixture("0.1.0", target, ReleaseFixture.fakeBossSched("0.1.0", state)).use { rel ->
            val e = assertFailsWith<SchedDownloader.InstallException> { SchedDownloader(rel.baseUrl, "9.9.9").install(target, dest) }
            assertTrue(e.message!!.contains("HTTP 404") && e.message!!.contains("v9.9.9"), e.message)
        }
        assertTrue(!dest.exists())
    }

    @Test
    fun `binary reporting the wrong version is rejected`() {
        ReleaseFixture("0.1.0", target, ReleaseFixture.fakeBossSched("0.0.1", state)).use { rel ->
            val e = assertFailsWith<SchedDownloader.InstallException> { SchedDownloader(rel.baseUrl, "0.1.0").install(target, dest) }
            assertTrue(e.message!!.contains("reports 0.0.1"), e.message)
        }
        assertTrue(!dest.exists())
    }
}
