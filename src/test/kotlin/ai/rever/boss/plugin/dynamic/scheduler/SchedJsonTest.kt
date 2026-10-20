package ai.rever.boss.plugin.dynamic.scheduler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixtures are boss-sched 0.1.0 output (`--json status|list|history`). */
class SchedJsonTest {

    @Test
    fun `status`() {
        val s = SchedJson.parseStatus(
            """
            {
              "home": "/Users/me/.boss/scheduler",
              "jobs": 2,
              "pid": 51616,
              "running": 1,
              "started_at": "2026-10-20T07:11:00.923151Z",
              "version": "0.1.0"
            }
            """.trimIndent(),
        )
        assertEquals(SchedStatus(51616, "0.1.0", "/Users/me/.boss/scheduler", "2026-10-20T07:11:00.923151Z", 2, 1), s)
    }

    @Test
    fun `version`() {
        assertEquals("0.1.0", SchedJson.parseVersion("""{"version":"0.1.0"}"""))
        assertNull(SchedJson.parseVersion("boss-sched 0.1.0"))
        assertNull(SchedJson.parseVersion(""))
    }

    @Test
    fun `list with cron, at, running and disabled jobs`() {
        val jobs = SchedJson.parseJobs(
            """
            [
              {"spec": {"id": "nightly-backup", "name": "Nightly backup",
                        "schedule": {"kind": "cron", "expr": "30 2 * * *", "tz": "America/Los_Angeles"},
                        "command": ["/usr/local/bin/backup", "--quiet"], "cwd": null, "env": {},
                        "timeout_seconds": 600, "max_lateness_seconds": 3600, "kill_grace_seconds": 10,
                        "delete_after_success": false, "enabled": true, "on_failure": null,
                        "max_consecutive_failures": 5, "future_field": 1},
               "state": {"next_run_at": "2026-10-21T09:30:00Z", "consecutive_failures": 0,
                         "disabled_reason": null,
                         "running": {"run_id": "r1", "pid": 123, "pid_start": 9, "started_at": "2026-10-20T09:30:00Z",
                                     "trigger": "schedule", "slot": "2026-10-20T09:30:00Z", "missed_slots": 1},
                         "last_status": "success", "last_finished_at": "2026-10-19T09:30:04Z"}},
              {"spec": {"id": "ping", "name": "ping", "schedule": {"kind": "at", "at": "2026-10-20T17:00:00Z"},
                        "command": ["/sbin/ping", "-c1", "1.1.1.1"], "enabled": false, "max_consecutive_failures": 5},
               "state": {"next_run_at": null, "consecutive_failures": 5,
                         "disabled_reason": "disabled after 5 consecutive failures", "running": null,
                         "last_status": "failed", "last_finished_at": null}}
            ]
            """.trimIndent(),
        )
        assertEquals(2, jobs.size)
        val a = jobs[0]
        assertEquals("nightly-backup", a.id)
        assertEquals("Nightly backup", a.name)
        assertEquals("30 2 * * * America/Los_Angeles", a.schedule)
        assertEquals(listOf("/usr/local/bin/backup", "--quiet"), a.command)
        assertTrue(a.enabled)
        assertTrue(a.isRunning)
        assertEquals("2026-10-20T09:30:00Z", a.runningSince)
        assertEquals("success", a.lastStatus)
        assertEquals("2026-10-21T09:30:00Z", a.nextRunAt)

        val b = jobs[1]
        assertEquals("at 2026-10-20T17:00:00Z", b.schedule)
        assertFalse(b.enabled)
        assertFalse(b.isRunning)
        assertNull(b.nextRunAt)
        assertEquals(5, b.consecutiveFailures)
        assertEquals("disabled after 5 consecutive failures", b.disabledReason)
    }

    @Test
    fun `empty list and bad shapes`() {
        assertEquals(emptyList(), SchedJson.parseJobs("[]"))
        assertFailsWith<IllegalArgumentException> { SchedJson.parseJobs("""{"error":"x"}""") }
        assertFailsWith<IllegalArgumentException> { SchedJson.parseJobs("""[{"state":{}}]""") }
    }

    @Test
    fun `history`() {
        val runs = SchedJson.parseHistory(
            """
            [
              {"run_id": "20261020T093000-1", "job_id": "nightly-backup", "trigger": "catch_up",
               "slot": "2026-10-20T09:30:00Z", "missed_slots": 3,
               "started_at": "2026-10-20T09:31:00Z", "finished_at": "2026-10-20T09:31:04Z",
               "status": "success", "exit_code": 0, "signal": null, "detail": null,
               "stdout": "done\n", "stderr": ""},
              {"run_id": "r2", "job_id": "nightly-backup", "trigger": "manual", "slot": null, "missed_slots": 0,
               "started_at": "2026-10-20T10:00:00Z", "finished_at": "2026-10-20T10:10:00Z",
               "status": "timeout", "exit_code": null, "signal": 9, "detail": "timed out after 600s"}
            ]
            """.trimIndent(),
        )
        assertEquals(2, runs.size)
        assertEquals("catch_up", runs[0].trigger)
        assertEquals(3, runs[0].missedSlots)
        assertEquals(0, runs[0].exitCode)
        assertEquals("done\n", runs[0].stdout)
        assertEquals("timeout", runs[1].status)
        assertNull(runs[1].exitCode)
        assertEquals(9, runs[1].signal)
        assertEquals("", runs[1].stderr)
        assertEquals("signal 9", SchedFormat.exit(runs[1]))
        assertEquals("exit 0", SchedFormat.exit(runs[0]))
    }
}

class SchedFormatTest {
    @Test
    fun `tail marks cut output`() {
        assertEquals("abc", SchedFormat.tail("abc\n", 10))
        assertEquals("[... 7 chars cut]\n789", SchedFormat.tail("0123456789", 3))
    }

    @Test
    fun `relative times`() {
        val now = java.time.Instant.parse("2026-10-20T10:00:00Z")
        assertEquals("in 1h 30m", SchedFormat.relative("2026-10-20T11:30:00Z", now))
        assertEquals("5m ago", SchedFormat.relative("2026-10-20T09:55:00Z", now))
        assertEquals("", SchedFormat.relative(null, now))
        assertEquals("-", SchedFormat.time(null))
        assertEquals("garbage", SchedFormat.time("garbage"))
    }
}
