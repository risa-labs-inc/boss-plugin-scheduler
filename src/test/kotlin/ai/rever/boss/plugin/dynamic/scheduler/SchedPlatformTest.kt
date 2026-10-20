package ai.rever.boss.plugin.dynamic.scheduler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchedPlatformTest {

    @Test
    fun `target triples match the release matrix`() {
        assertEquals("aarch64-apple-darwin", SchedPlatform.targetTriple("Mac OS X", "aarch64"))
        assertEquals("x86_64-apple-darwin", SchedPlatform.targetTriple("Mac OS X", "x86_64"))
        assertEquals("x86_64-unknown-linux-gnu", SchedPlatform.targetTriple("Linux", "amd64"))
        assertEquals("aarch64-unknown-linux-gnu", SchedPlatform.targetTriple("Linux", "aarch64"))
        assertEquals("aarch64-apple-darwin", SchedPlatform.targetTriple("Darwin", "arm64"))
    }

    @Test
    fun `no target for windows or unknown cpus`() {
        assertNull(SchedPlatform.targetTriple("Windows 11", "amd64"))
        assertNull(SchedPlatform.targetTriple("Linux", "x86"))
        assertNull(SchedPlatform.targetTriple("Linux", "riscv64"))
        assertEquals(SchedOs.UNSUPPORTED, SchedPlatform.os("Windows 10"))
        assertEquals(SchedOs.MAC, SchedPlatform.os("Mac OS X"))
        assertEquals(SchedOs.LINUX, SchedPlatform.os("Linux"))
    }

    @Test
    fun `release urls follow the contract`() {
        assertEquals(
            "https://github.com/risa-labs-inc/BossScheduler/releases/download/v0.1.0/boss-sched-0.1.0-aarch64-apple-darwin.tar.gz",
            SchedRelease.archiveUrl(SchedRelease.DEFAULT_BASE_URL, "0.1.0", "aarch64-apple-darwin"),
        )
        assertEquals(
            "https://github.com/risa-labs-inc/BossScheduler/releases/download/v0.1.0/SHA256SUMS",
            SchedRelease.checksumsUrl(SchedRelease.DEFAULT_BASE_URL + "/", "0.1.0"),
        )
    }

    @Test
    fun `version comparison is numeric`() {
        assertTrue(SchedVersion.compare("0.1.0", "0.1.0") == 0)
        assertTrue(SchedVersion.compare("0.0.9", "0.1.0") < 0)
        assertTrue(SchedVersion.compare("0.10.0", "0.9.0") > 0)
        assertTrue(SchedVersion.compare("v0.1", "0.1.0") == 0)
        assertTrue(SchedVersion.compare("0.2.0-rc.1", "0.1.9") > 0)
    }
}
