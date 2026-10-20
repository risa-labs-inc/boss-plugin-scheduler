package ai.rever.boss.plugin.dynamic.scheduler

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SchedPathsTest {

    /** Verbatim `boss-sched print-agent` output from 0.1.0 (path changed). */
    private val plist = """
        <?xml version="1.0" encoding="UTF-8"?>
        <plist version="1.0">
        <dict>
          <key>Label</key>
          <string>ai.rever.boss.scheduler</string>
          <key>ProgramArguments</key>
          <array>
            <string>/Users/a &amp; b/.local/bin/boss-sched</string>
            <string>daemon</string>
          </array>
          <key>EnvironmentVariables</key>
          <dict>
            <key>PATH</key>
            <string>/usr/bin:/bin</string>
          </dict>
        </dict>
        </plist>
    """.trimIndent()

    @Test
    fun `plist program`() {
        assertEquals("/Users/a & b/.local/bin/boss-sched", SchedPaths.plistProgram(plist))
        assertNull(SchedPaths.plistProgram("<plist></plist>"))
    }

    @Test
    fun `systemd unit program`() {
        val unit = """
            [Service]
            ExecStart="/home/me/50%%/\"q\"/boss-sched" --home "/x" daemon
            Restart=on-failure
        """.trimIndent()
        assertEquals("/home/me/50%/\"q\"/boss-sched", SchedPaths.unitProgram(unit))
        assertEquals("/usr/bin/boss-sched", SchedPaths.unitProgram("ExecStart=/usr/bin/boss-sched daemon"))
        assertNull(SchedPaths.unitProgram("[Unit]"))
    }

    @Test
    fun `path merge dedupes and keeps order`() {
        assertEquals("/a:/b:/c", SchedPaths.mergePath(listOf("/a", "/b", ""), "/b:/c::/a"))
    }

    @Test
    fun `login shell path is read between markers`() {
        val m = SchedPaths.PATH_MARKER
        assertEquals("/x:/y", SchedPaths.parseMarkedPath("motd noise\n$m/x:/y$m\n"))
        assertNull(SchedPaths.parseMarkedPath("no markers"))
    }

    @Test
    fun `candidates prefer the service binary and classify the managed one`() {
        val home = Files.createTempDirectory("sched-paths").toFile()
        try {
            val paths = SchedPaths(home, mapOf("PATH" to ""))
            fun exe(f: File) = f.apply { parentFile.mkdirs(); writeText("#!/bin/sh\n"); setExecutable(true) }
            val local = exe(File(home, ".local/bin/boss-sched"))
            val managed = exe(paths.managedBinary)
            val svc = exe(File(home, "custom/boss-sched"))
            assertEquals(listOf(svc, local, managed).map { it.canonicalPath }, paths.candidates(svc).map { it.canonicalPath })
            assertEquals(listOf(local, managed).map { it.canonicalPath }, paths.candidates(null).map { it.canonicalPath })
            assertEquals(SchedSource.PLUGIN, paths.sourceOf(managed))
            assertEquals(SchedSource.EXTERNAL, paths.sourceOf(local))
            assertEquals(File(home, ".boss/scheduler"), paths.schedHome)
            assertEquals(File("/custom"), SchedPaths(home, mapOf("BOSS_SCHED_HOME" to "/custom")).schedHome)
        } finally {
            home.deleteRecursively()
        }
    }
}
