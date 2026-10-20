package ai.rever.boss.plugin.dynamic.scheduler

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Parsing of `boss-sched --json` output, read against boss-sched 0.1.0
 * (`src/job.rs` serde shapes and `src/daemon.rs::handle`).
 *
 * Lenient on purpose: unknown fields are ignored and a missing optional field
 * becomes null, so an additive CLI change does not blank the panel. A shape
 * that is not what we expect at all throws [IllegalArgumentException].
 */
object SchedJson {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseVersion(text: String): String? =
        runCatching { obj(json.parseToJsonElement(text)).str("version") }.getOrNull()

    fun parseStatus(text: String): SchedStatus {
        val o = obj(json.parseToJsonElement(text))
        return SchedStatus(
            pid = o.long("pid"),
            version = o.str("version").orEmpty(),
            home = o.str("home").orEmpty(),
            startedAt = o.str("started_at"),
            jobs = o.int("jobs") ?: 0,
            running = o.int("running") ?: 0,
        )
    }

    fun parseJobs(text: String): List<SchedJob> {
        val arr = json.parseToJsonElement(text) as? JsonArray
            ?: throw IllegalArgumentException("expected a JSON array from `list`")
        return arr.map { parseJob(obj(it)) }
    }

    /** One `{spec, state}` object, as returned by `list`, `enable` and `disable`. */
    fun parseJob(o: JsonObject): SchedJob {
        val spec = o["spec"] as? JsonObject ?: throw IllegalArgumentException("job without spec")
        val state = o["state"] as? JsonObject ?: JsonObject(emptyMap())
        val running = state["running"] as? JsonObject
        return SchedJob(
            id = spec.str("id").orEmpty(),
            name = spec.str("name").orEmpty(),
            enabled = spec.bool("enabled") ?: true,
            schedule = describeSchedule(spec["schedule"] as? JsonObject),
            command = (spec["command"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
            nextRunAt = state.str("next_run_at"),
            lastStatus = state.str("last_status"),
            lastFinishedAt = state.str("last_finished_at"),
            consecutiveFailures = state.int("consecutive_failures") ?: 0,
            maxConsecutiveFailures = spec.int("max_consecutive_failures"),
            disabledReason = state.str("disabled_reason"),
            runningSince = running?.let { it.str("started_at") ?: "" },
        )
    }

    fun parseHistory(text: String): List<SchedRun> {
        val arr = json.parseToJsonElement(text) as? JsonArray
            ?: throw IllegalArgumentException("expected a JSON array from `history`")
        return arr.map { el ->
            val o = obj(el)
            SchedRun(
                runId = o.str("run_id").orEmpty(),
                jobId = o.str("job_id").orEmpty(),
                trigger = o.str("trigger").orEmpty(),
                missedSlots = o.int("missed_slots") ?: 0,
                startedAt = o.str("started_at"),
                finishedAt = o.str("finished_at"),
                status = o.str("status").orEmpty(),
                exitCode = o.int("exit_code"),
                signal = o.int("signal"),
                detail = o.str("detail"),
                stdout = o.str("stdout").orEmpty(),
                stderr = o.str("stderr").orEmpty(),
            )
        }
    }

    fun describeSchedule(s: JsonObject?): String = when (s?.str("kind")) {
        "cron" -> listOfNotNull(s.str("expr"), s.str("tz")).joinToString(" ")
        "at" -> "at ${s.str("at").orEmpty()}"
        null -> "-"
        else -> s.toString()
    }

    private fun obj(e: JsonElement): JsonObject =
        e as? JsonObject ?: throw IllegalArgumentException("expected a JSON object, got ${e::class.simpleName}")

    private fun JsonObject.prim(key: String): JsonPrimitive? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }

    private fun JsonObject.str(key: String): String? = prim(key)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = prim(key)?.intOrNull
    private fun JsonObject.long(key: String): Long? = prim(key)?.longOrNull
    private fun JsonObject.bool(key: String): Boolean? = prim(key)?.booleanOrNull
}
