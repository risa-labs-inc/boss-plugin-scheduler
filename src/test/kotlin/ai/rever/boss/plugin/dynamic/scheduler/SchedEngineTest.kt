package ai.rever.boss.plugin.dynamic.scheduler

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The register-time install flow against a temp home, a fake service manager
 * and a local release server. Nothing here touches launchctl, systemctl or the
 * real ~/.boss.
 */
class SchedEngineTest {

    private val dir: File = Files.createTempDirectory("sched-engine").toFile()
    private val home = File(dir, "home").apply { mkdirs() }
    private val state = File(dir, "state").apply { mkdirs() }
    private val paths = SchedPaths(home, mapOf("PATH" to ""))
    private val target = "x86_64-unknown-linux-gnu"

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private class FakeService(override val serviceFile: File, private val state: File) : SchedService {
        val calls = mutableListOf<String>()
        var binary: File? = null
        override fun serviceBinary() = binary
        override suspend fun load(): String? { calls += "load"; File(state, "running").createNewFile(); return null }
        override suspend fun reload(): String? { calls += "reload"; File(state, "running").createNewFile(); return null }
        override suspend fun restart(): String? { calls += "restart"; return null }
        override val manualStartHint = "start it"
    }

    private fun engine(rel: ReleaseFixture?, service: FakeService, os: SchedOs = SchedOs.LINUX) = SchedEngine(
        paths = paths,
        os = os,
        target = target,
        downloader = SchedDownloader(rel?.baseUrl ?: "http://127.0.0.1:9", SchedRelease.PINNED_VERSION),
        serviceFactory = { _, _ -> service },
        jobPath = { "/fake/bin:/usr/bin" },
        startWaitMs = 3_000,
    )

    @Test
    fun `nothing installed - downloads, writes the service, starts it`(): Unit = runBlocking {
        val service = FakeService(File(home, "svc"), state)
        ReleaseFixture(SchedRelease.PINNED_VERSION, target, ReleaseFixture.fakeBossSched(SchedRelease.PINNED_VERSION, state)).use { rel ->
            val e = engine(rel, service)
            e.ensure()
            val ready = assertIs<SchedSetup.Ready>(e.setup.value)
            assertEquals(paths.managedBinary.canonicalPath, ready.binary.file.canonicalPath)
            assertEquals(SchedSource.PLUGIN, ready.binary.source)
            assertEquals(listOf("reload"), service.calls)
            assertEquals("/fake/bin:/usr/bin", File(state, "agent-path").readText())
            assertIs<SchedDaemon.Running>(e.daemon.value)
        }
    }

    @Test
    fun `existing running install outside BOSS is left alone`(): Unit = runBlocking {
        val external = File(home, ".local/bin/boss-sched").apply {
            parentFile.mkdirs()
            writeBytes(ReleaseFixture.fakeBossSched("0.0.5", state))
            setExecutable(true)
        }
        File(state, "running").createNewFile()
        val service = FakeService(File(home, "svc").apply { writeText("x") }, state).apply { binary = external }
        ReleaseFixture(SchedRelease.PINNED_VERSION, target, ByteArray(1)).use { rel ->
            val e = engine(rel, service)
            e.ensure()
            val ready = assertIs<SchedSetup.Ready>(e.setup.value)
            assertEquals(external.canonicalPath, ready.binary.file.canonicalPath)
            assertEquals(SchedSource.EXTERNAL, ready.binary.source)
            assertEquals(0, rel.requests.get(), "must not download over an external install")
            assertTrue(service.calls.isEmpty())
            assertTrue(!File(state, "agent-path").exists(), "must not rewrite the service file")
        }
    }

    @Test
    fun `installed but stopped with a matching service file - just loads it`(): Unit = runBlocking {
        val bin = paths.managedBinary.apply {
            parentFile.mkdirs()
            writeBytes(ReleaseFixture.fakeBossSched(SchedRelease.PINNED_VERSION, state))
            setExecutable(true)
        }
        val service = FakeService(File(home, "svc").apply { writeText("x") }, state).apply { binary = bin }
        val e = engine(null, service)
        e.ensure()
        assertIs<SchedSetup.Ready>(e.setup.value)
        assertEquals(listOf("load"), service.calls)
        assertTrue(!File(state, "agent-path").exists())
    }

    @Test
    fun `outdated plugin-managed binary is replaced and the service restarted`(): Unit = runBlocking {
        paths.managedBinary.apply {
            parentFile.mkdirs()
            writeBytes(ReleaseFixture.fakeBossSched("0.0.9", state))
            setExecutable(true)
        }
        File(state, "running").createNewFile()
        val service = FakeService(File(home, "svc").apply { writeText("x") }, state).apply { binary = paths.managedBinary }
        ReleaseFixture(SchedRelease.PINNED_VERSION, target, ReleaseFixture.fakeBossSched(SchedRelease.PINNED_VERSION, state)).use { rel ->
            val e = engine(rel, service)
            e.ensure()
            val ready = assertIs<SchedSetup.Ready>(e.setup.value)
            assertEquals(SchedRelease.PINNED_VERSION, ready.binary.version)
            assertEquals(listOf("restart"), service.calls)
        }
    }

    @Test
    fun `failed download surfaces as Failed, never throws`(): Unit = runBlocking {
        val service = FakeService(File(home, "svc"), state)
        val e = engine(null, service)
        e.ensure()
        val failed = assertIs<SchedSetup.Failed>(e.setup.value)
        assertTrue(failed.reason.contains("Could not install"), failed.reason)
        assertTrue(service.calls.isEmpty())
    }

    @Test
    fun `unsupported os`(): Unit = runBlocking {
        val e = SchedEngine(paths = paths, os = SchedOs.UNSUPPORTED, target = null)
        e.ensure()
        assertIs<SchedSetup.Unsupported>(e.setup.value)
        assertEquals(null, e.cli())
    }
}
