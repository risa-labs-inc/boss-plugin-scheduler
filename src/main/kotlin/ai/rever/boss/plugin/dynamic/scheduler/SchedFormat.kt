package ai.rever.boss.plugin.dynamic.scheduler

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Display helpers. Pure. */
object SchedFormat {

    private val local = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss")

    /** RFC 3339 instant in the local zone, or "-" when absent/unparseable. */
    fun time(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String {
        if (iso.isNullOrBlank()) return "-"
        return runCatching { local.format(Instant.parse(iso).atZone(zone)) }.getOrDefault(iso)
    }

    /** "in 3h 5m" / "12m ago", relative to [now]. */
    fun relative(iso: String?, now: Instant = Instant.now()): String {
        if (iso.isNullOrBlank()) return ""
        val t = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
        val d = Duration.between(now, t)
        val s = d.abs().seconds
        val text = when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            s < 86_400 -> "${s / 3600}h ${(s % 3600) / 60}m"
            else -> "${s / 86_400}d ${(s % 86_400) / 3600}h"
        }
        return if (d.isNegative) "$text ago" else "in $text"
    }

    fun exit(r: SchedRun): String = when {
        r.exitCode != null -> "exit ${r.exitCode}"
        r.signal != null -> "signal ${r.signal}"
        else -> "-"
    }

    /** Last [max] characters of [s], marked when cut. */
    fun tail(s: String, max: Int): String {
        val t = s.trimEnd()
        return if (t.length <= max) t else "[... ${t.length - max} chars cut]\n" + t.takeLast(max)
    }
}
